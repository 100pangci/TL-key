package com.ywpc.tlkey.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.control.EcuResult
import com.ywpc.tlkey.databinding.ActivityEcuBinding
import com.ywpc.tlkey.databinding.ItemEcuSettingBinding
import com.ywpc.tlkey.protocol.EcuChannel
import com.ywpc.tlkey.protocol.EcuCodec
import com.ywpc.tlkey.protocol.EcuState
import com.ywpc.tlkey.protocol.ProtocolFamily
import com.ywpc.tlkey.protocol.ProtocolSelector
import com.ywpc.tlkey.vehicle.EcuAbility
import com.ywpc.tlkey.vehicle.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ECU 驾驶模式设置。
 *
 * 数据来源：
 * - `app/car/getAbility` 的 `ecu*Func`：官方 UI 仅在值为 1 时显示设置项；
 * - BLE fcc0 服务的 fcc1/fbb1 原始特征：读取状态与能力位，写入单 bit 改动。
 *
 * 官方写入后不会立即回读，本页同样提示“已下发（未回读确认）”，
 * 用户可再次点“读取车辆 ECU”确认真实状态。
 */
class EcuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEcuBinding
    private lateinit var app: App
    private var state: EcuState? = null
    private var serverAbility: EcuAbility? = null
    private var abilityError = false
    private var busy = false
    private var pendingRead = false

    private data class Setting(
        val type: Int,
        val titleRes: Int,
        val descriptionRes: Int,
        val kind: Kind,
    )

    private enum class Kind { FLAG, HDC, ER, PODG }

    private val settings = listOf(
        Setting(EcuCodec.TYPE_TCS, R.string.ecu_tcs_title, R.string.ecu_tcs_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_SFR, R.string.ecu_sfr_title, R.string.ecu_sfr_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_PCP, R.string.ecu_pcp_title, R.string.ecu_pcp_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_HDC, R.string.ecu_hdc_title, R.string.ecu_hdc_desc, Kind.HDC),
        Setting(EcuCodec.TYPE_CC, R.string.ecu_cc_title, R.string.ecu_cc_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_REVERSE, R.string.ecu_reverse_title, R.string.ecu_reverse_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_SFS, R.string.ecu_sfs_title, R.string.ecu_sfs_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_ER, R.string.ecu_er_title, R.string.ecu_er_desc, Kind.ER),
        Setting(EcuCodec.TYPE_LBCM, R.string.ecu_lbcm_title, R.string.ecu_lbcm_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_PODG, R.string.ecu_podg_title, R.string.ecu_podg_desc, Kind.PODG),
        Setting(
            EcuCodec.TYPE_HILL_PARKING,
            R.string.ecu_hill_parking_title,
            R.string.ecu_hill_parking_desc,
            Kind.FLAG,
        ),
        Setting(
            EcuCodec.TYPE_SLIP_SLOPE,
            R.string.ecu_slip_slope_title,
            R.string.ecu_slip_slope_desc,
            Kind.FLAG,
        ),
        Setting(EcuCodec.TYPE_NITRO, R.string.ecu_nitro_title, R.string.ecu_nitro_desc, Kind.FLAG),
        Setting(EcuCodec.TYPE_TEMP, R.string.ecu_temp_title, R.string.ecu_temp_desc, Kind.FLAG),
        Setting(
            EcuCodec.TYPE_MOTOR_TEMP,
            R.string.ecu_motor_temp_title,
            R.string.ecu_motor_temp_desc,
            Kind.FLAG,
        ),
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val run = pendingRead
        pendingRead = false
        if (!run) return@registerForActivityResult
        if (missingPermissions().isEmpty()) {
            startRead()
        } else {
            showResult(getString(R.string.ble_permission_needed), isError = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        if (!app.loginManager.hasSession()) {
            openLogin()
            return
        }

        binding = ActivityEcuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEcuRead.setOnClickListener { requestRead() }
        binding.tvEcuStatus.text = getString(R.string.ecu_not_read)
        renderVehicle()

        val vehicle = vehicle()
        if (vehicle == null) {
            binding.btnEcuRead.isEnabled = false
            showResult(getString(R.string.ecu_vehicle_required), isError = true)
            return
        }
        if (ProtocolSelector.familyFor(vehicle.modelType) == ProtocolFamily.LEGACY_78) {
            binding.btnEcuRead.isEnabled = false
            binding.tvEcuChannel.text = getString(R.string.ecu_channel_unsupported)
            showResult(getString(R.string.ecu_unsupported), isError = true)
            return
        }

        loadServerAbility(vehicle)
        requestRead()
    }

    private fun vehicle(): Vehicle? = app.controller.cachedVehicle()

    private fun renderVehicle() {
        val vehicle = vehicle()
        binding.tvEcuVehicle.text = vehicle?.let {
            "${it.name} · ${it.protocolLabel}"
        } ?: getString(R.string.ecu_vehicle_required)
        binding.tvEcuChannel.text = getString(R.string.ecu_channel_waiting)
    }

    private fun loadServerAbility(vehicle: Vehicle) {
        if (vehicle.imei.isBlank()) {
            abilityError = true
            return
        }
        lifecycleScope.launch {
            try {
                val token = app.authRepository.getValidToken()
                val ability = withContext(Dispatchers.IO) {
                    app.ecuApi.ability(token, vehicle.imei)
                }
                serverAbility = ability
                renderSettings()
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                abilityError = true
                renderSettings()
            }
        }
    }

    private fun requestRead() {
        if (busy) return
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingRead = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        startRead()
    }

    private fun startRead() {
        if (busy) return
        busy = true
        updateUi()
        renderSettings()
        binding.tvEcuStatus.text = getString(R.string.ecu_reading)
        showResult(getString(R.string.ecu_reading), isError = false)

        lifecycleScope.launch {
            try {
                when (val result = app.controller.readEcu()) {
                    is EcuResult.Success -> {
                        state = result.state
                        binding.tvEcuStatus.text = getString(
                            R.string.ecu_read_ok,
                            result.state?.channel?.name?.lowercase() ?: "UNKNOWN",
                        )
                        binding.tvEcuChannel.text = result.state?.let {
                            getString(R.string.ecu_channel_value, it.channel.name.lowercase())
                        } ?: getString(R.string.ecu_channel_unknown)
                        showResult(result.message, isError = false)
                    }

                    is EcuResult.Failure -> {
                        state = null
                        binding.tvEcuStatus.text = getString(R.string.ecu_read_failed)
                        showResult(result.message, isError = true)
                    }
                }
            } finally {
                busy = false
                updateUi()
                renderSettings()
            }
        }
    }

    private fun renderSettings() {
        binding.containerEcuSettings.removeAllViews()
        val current = state
        if (current == null) {
            val placeholder = TextView(this).apply {
                text = getString(R.string.ecu_read_first)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                textSize = 13f
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            binding.containerEcuSettings.addView(placeholder)
            binding.tvEcuHidden.isVisible = false
            return
        }

        val inflater = LayoutInflater.from(this)
        var shown = 0
        settings.forEach { setting ->
            if (!shouldShow(setting.type, current)) return@forEach
            shown++
            val item = ItemEcuSettingBinding.inflate(
                inflater,
                binding.containerEcuSettings,
                false,
            )
            item.tvEcuItemTitle.text = getString(setting.titleRes)
            item.tvEcuItemSubtitle.text = getString(setting.descriptionRes)
            when (setting.kind) {
                Kind.FLAG -> {
                    item.switchEcu.isVisible = true
                    item.btnEcuValue.isVisible = false
                    val value = current.flag(setting.type) == true
                    item.switchEcu.setOnCheckedChangeListener(null)
                    item.switchEcu.isChecked = value
                    item.switchEcu.isEnabled = !busy
                    item.switchEcu.setOnCheckedChangeListener { _, checked ->
                        if (!busy && checked != (state?.flag(setting.type) == true)) {
                            applyFlag(setting.type, checked, getString(setting.titleRes))
                        }
                    }
                }

                Kind.HDC, Kind.ER, Kind.PODG -> {
                    item.switchEcu.isVisible = false
                    item.btnEcuValue.isVisible = true
                    val currentValue = current.bits(setting.type)
                    item.btnEcuValue.text = optionLabel(setting.type, currentValue)
                    item.btnEcuValue.isEnabled = !busy
                    item.btnEcuValue.setOnClickListener {
                        showOptions(setting, getString(setting.titleRes))
                    }
                }
            }
            binding.containerEcuSettings.addView(item.root)
        }

        if (shown == 0) {
            val empty = TextView(this).apply {
                text = getString(R.string.ecu_no_supported)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                textSize = 13f
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            binding.containerEcuSettings.addView(empty)
        }

        val hidden = settings.size - shown
        val notes = buildList {
            if (hidden > 0) add(getString(R.string.ecu_hidden_count, hidden))
            if (abilityError) add(getString(R.string.ecu_ability_unavailable))
        }
        binding.tvEcuHidden.text = notes.joinToString("\n")
        binding.tvEcuHidden.isVisible = notes.isNotEmpty()
    }

    private fun shouldShow(type: Int, current: EcuState): Boolean {
        if (current.channel == EcuChannel.FCC1 &&
            (type == EcuCodec.TYPE_TEMP || type == EcuCodec.TYPE_MOTOR_TEMP)
        ) {
            return false
        }
        val server = serverAbility?.supports(type)
        if (server == false) return false
        if (server == true) return true
        return current.abilities.supports(type) == true
    }

    private fun applyFlag(type: Int, enabled: Boolean, label: String) {
        val current = state ?: return
        val next = try {
            EcuCodec.setFlag(current, type, enabled)
        } catch (t: Throwable) {
            showResult(t.message ?: getString(R.string.ecu_write_failed), isError = true)
            renderSettings()
            return
        }
        write(next, "$label ${if (enabled) "开" else "关"}")
    }

    private fun showOptions(setting: Setting, label: String) {
        val current = state ?: return
        val currentValue = current.bits(setting.type)
        val (optionRes, valueRes) = when (setting.kind) {
            Kind.HDC -> R.array.ecu_hdc_options to R.array.ecu_hdc_values
            Kind.ER -> R.array.ecu_er_options to R.array.ecu_er_values
            Kind.PODG -> R.array.ecu_podg_options to R.array.ecu_podg_values
            Kind.FLAG -> return
        }
        val options = resources.getStringArray(optionRes).toMutableList()
        val values = resources.getStringArray(valueRes).toMutableList()

        // 官方只在 PodgFuncType == 4 时提供第 4 档；若控制器已上报 100，也一并展示。
        if (setting.type == EcuCodec.TYPE_PODG &&
            (current.abilities.podgFuncType == 4 || currentValue == "100")
        ) {
            if (values.none { it == "100" }) {
                options += getString(R.string.ecu_podg_4)
                values += "100"
            }
        }

        val checked = values.indexOf(currentValue)
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val value = values[which]
                if (value != currentValue) {
                    val next = try {
                        EcuCodec.setBits(current, setting.type, value)
                    } catch (t: Throwable) {
                        showResult(
                            t.message ?: getString(R.string.ecu_write_failed),
                            isError = true,
                        )
                        return@setSingleChoiceItems
                    }
                    write(next, "$label ${options[which]}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun write(next: EcuState, label: String) {
        if (busy) return
        busy = true
        updateUi()
        renderSettings()
        binding.tvEcuStatus.text = getString(R.string.ecu_writing)
        showResult(getString(R.string.ecu_writing), isError = false)

        lifecycleScope.launch {
            try {
                when (val result = app.controller.writeEcu(next)) {
                    is EcuResult.Success -> {
                        state = result.state ?: next
                        binding.tvEcuStatus.text = getString(R.string.ecu_write_ok, label)
                        showResult(
                            getString(R.string.ecu_write_not_read, label),
                            isError = false,
                        )
                    }

                    is EcuResult.Failure -> {
                        binding.tvEcuStatus.text = getString(R.string.ecu_write_failed)
                        showResult(result.message, isError = true)
                    }
                }
            } finally {
                busy = false
                updateUi()
                renderSettings()
            }
        }
    }

    private fun optionLabel(type: Int, value: String?): String {
        if (value == null) return getString(R.string.ecu_value_unknown)
        val optionRes = when (type) {
            EcuCodec.TYPE_HDC -> R.array.ecu_hdc_options
            EcuCodec.TYPE_ER -> R.array.ecu_er_options
            EcuCodec.TYPE_PODG -> R.array.ecu_podg_options
            else -> return value
        }
        val valueRes = when (type) {
            EcuCodec.TYPE_HDC -> R.array.ecu_hdc_values
            EcuCodec.TYPE_ER -> R.array.ecu_er_values
            EcuCodec.TYPE_PODG -> R.array.ecu_podg_values
            else -> return value
        }
        val values = resources.getStringArray(valueRes)
        val index = values.indexOf(value)
        return if (index >= 0) {
            resources.getStringArray(optionRes)[index]
        } else if (type == EcuCodec.TYPE_PODG && value == "100") {
            getString(R.string.ecu_podg_4)
        } else {
            value
        }
    }

    private fun updateUi() {
        binding.btnEcuRead.isEnabled = !busy
        binding.btnEcuRead.text = getString(
            if (busy) R.string.ecu_working else R.string.ecu_read,
        )
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.tvEcuResult.text = message
        binding.tvEcuResult.isVisible = message.isNotBlank()
        binding.tvEcuResult.setTextColor(
            MaterialColors.getColor(
                binding.tvEcuResult,
                if (isError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

    private fun missingPermissions(): List<String> {
        val needsScan = vehicle()?.mayNeedScan ?: true
        if (Build.VERSION.SDK_INT < 31) {
            return if (needsScan && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                emptyList()
            }
        }
        return buildList {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (needsScan && !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
