package com.ywpc.tlkey.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.databinding.ActivityOtaBinding
import com.ywpc.tlkey.databinding.ItemOtaUpdateBinding
import com.ywpc.tlkey.vehicle.OtaProgress
import com.ywpc.tlkey.vehicle.OtaUpdate
import com.ywpc.tlkey.vehicle.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OTA 入口。
 *
 * 官方 3.6.2 的主流程是：App 调 `getAllFirmVersion` 查询版本，用户确认后调
 * `upGradation` 让服务器给车辆下发升级任务，再轮询 `otaProgress` 展示进度。
 * 固件下载与刷写由车辆/ECU 自行完成；本页不上传、不下载、不刷写任何固件。
 */
class OtaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtaBinding
    private lateinit var app: App
    private var busy = false
    private var progressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        if (!app.loginManager.hasSession()) {
            openLogin()
            return
        }

        binding = ActivityOtaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnOtaCheck.setOnClickListener { checkVersions() }
        binding.btnOtaRefreshProgress.setOnClickListener { fetchProgress(manual = true) }
        binding.tvOtaProgressValue.text = getString(R.string.ota_progress_unknown)
        renderVehicle()

        if (vehicle() == null) {
            showResult(getString(R.string.ota_vehicle_required), isError = true)
            binding.btnOtaCheck.isEnabled = false
            binding.btnOtaRefreshProgress.isEnabled = false
            showUpdates(emptyList(), getString(R.string.ota_vehicle_required))
            return
        }
        checkVersions()
    }

    override fun onStart() {
        super.onStart()
        if (::app.isInitialized && vehicle() != null) startProgressPolling()
    }

    override fun onStop() {
        progressJob?.cancel()
        progressJob = null
        super.onStop()
    }

    private fun vehicle(): Vehicle? = app.controller.cachedVehicle()

    private fun renderVehicle() {
        val vehicle = vehicle()
        binding.tvOtaVehicle.text = vehicle?.let {
            "${it.name} · ${it.protocolLabel}"
        } ?: getString(R.string.ota_vehicle_required)
    }

    private fun checkVersions() {
        if (busy) return
        val vehicle = vehicle() ?: return
        val imei = vehicle.imei.trim()
        val imeiGps = vehicle.imeiGps.trim()
        if (imei.isEmpty() && imeiGps.isEmpty()) {
            showResult(getString(R.string.ota_imei_required), isError = true)
            return
        }

        busy = true
        updateButtons()
        showResult(getString(R.string.ota_checking), isError = false)
        showUpdates(emptyList(), getString(R.string.ota_checking))

        lifecycleScope.launch {
            try {
                val token = app.authRepository.getValidToken()
                val info = withContext(Dispatchers.IO) {
                    app.otaApi.versions(token, imei, imeiGps)
                }
                val available = info.updates.filter { it.hasUpdate }
                if (available.isEmpty()) {
                    showUpdates(emptyList(), getString(R.string.ota_no_updates))
                    showResult(getString(R.string.ota_no_updates), isError = false)
                } else {
                    showUpdates(available, null)
                    showResult(
                        getString(R.string.ota_updates_found, available.size),
                        isError = false,
                    )
                }
                startProgressPolling()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                val message = t.message ?: getString(R.string.ota_check_failed)
                showUpdates(emptyList(), message)
                showResult(message, isError = true)
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun showUpdates(updates: List<OtaUpdate>, emptyText: String?) {
        binding.containerOtaUpdates.removeAllViews()
        if (updates.isEmpty()) {
            val view = TextView(this).apply {
                text = emptyText ?: getString(R.string.ota_no_updates)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                textSize = 13f
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            binding.containerOtaUpdates.addView(view)
            return
        }

        val inflater = LayoutInflater.from(this)
        updates.forEach { update ->
            val item = ItemOtaUpdateBinding.inflate(inflater, binding.containerOtaUpdates, false)
            item.tvOtaItemTitle.text = update.versionName.ifBlank {
                update.versionFullName.ifBlank { update.fileName.ifBlank { update.id } }
            }
            item.tvOtaItemMeta.text = buildMeta(update)
            item.tvOtaItemRemark.isVisible = update.remark.isNotBlank()
            item.tvOtaItemRemark.text = update.remark
            item.btnOtaItemUpgrade.isVisible = update.hasUpdate
            item.btnOtaItemUpgrade.setOnClickListener { confirmTrigger(update) }
            binding.containerOtaUpdates.addView(item.root)
        }
    }

    private fun buildMeta(update: OtaUpdate): String = buildList {
        add(getString(R.string.ota_item_platform, update.platform.ifBlank { "UNKNOWN" }))
        add(getString(R.string.ota_item_type, update.type.ifBlank { "UNKNOWN" }))
        if (update.versionFullName.isNotBlank() &&
            update.versionFullName != update.versionName
        ) {
            add(update.versionFullName)
        }
        if (update.forced) add(getString(R.string.ota_force))
    }.joinToString(" · ")

    private fun confirmTrigger(update: OtaUpdate) {
        if (busy) return
        val vehicle = vehicle() ?: return
        val imeiGps = vehicle.imeiGps.trim()
        if (imeiGps.isEmpty()) {
            showResult(getString(R.string.ota_imei_gps_required), isError = true)
            return
        }
        if (update.id.isBlank()) {
            showResult(getString(R.string.ota_invalid_update), isError = true)
            return
        }
        val version = update.versionName.ifBlank {
            update.versionFullName.ifBlank { update.fileName }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ota_confirm_title)
            .setMessage(getString(R.string.ota_confirm_message, vehicle.name, version))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ota_confirm_positive) { _, _ ->
                trigger(update, imeiGps)
            }
            .show()
    }

    private fun trigger(update: OtaUpdate, imeiGps: String) {
        if (busy) return
        busy = true
        updateButtons()
        showResult(getString(R.string.ota_triggering), isError = false)

        lifecycleScope.launch {
            try {
                val token = app.authRepository.getValidToken()
                withContext(Dispatchers.IO) {
                    app.otaApi.trigger(token, update.id, imeiGps)
                }
                showResult(getString(R.string.ota_triggered), isError = false)
                startProgressPolling()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showResult(
                    t.message ?: getString(R.string.ota_trigger_failed),
                    isError = true,
                )
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                fetchProgressNow()
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun fetchProgress(manual: Boolean) {
        if (manual) showResult(getString(R.string.ota_progress_refreshing), isError = false)
        lifecycleScope.launch {
            val progress = fetchProgressNow()
            if (manual) {
                val message = if (progress == null) {
                    getString(R.string.ota_progress_unknown)
                } else {
                    progressText(progress)
                }
                showResult(message, isError = false)
            }
        }
    }

    private suspend fun fetchProgressNow(): OtaProgress? {
        val vehicle = vehicle() ?: return null
        val imeiGps = vehicle.imeiGps.trim()
        if (imeiGps.isEmpty()) return null
        return try {
            val token = app.authRepository.getValidToken()
            val progress = withContext(Dispatchers.IO) {
                app.otaApi.progress(token, imeiGps)
            }
            renderProgress(progress)
            progress
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            // 后台轮询失败保持上一次展示，避免把网络抖动当成升级失败。
            null
        }
    }

    private fun renderProgress(progress: OtaProgress?) {
        if (progress == null) {
            binding.tvOtaProgressValue.text = getString(R.string.ota_progress_unknown)
            binding.tvOtaProgressStatus.text = getString(R.string.ota_progress_no_record)
            return
        }
        binding.tvOtaProgressValue.text = progress.percent?.let {
            getString(R.string.ota_progress_value, it.coerceIn(0, 100))
        } ?: getString(R.string.ota_progress_unknown)
        binding.tvOtaProgressStatus.text = progressText(progress)
    }

    private fun progressText(progress: OtaProgress): String = buildList {
        add(statusLabel(progress.status))
        if (progress.percent != null) add("${progress.percent.coerceIn(0, 100)}%")
        if (progress.type != null) add(getString(R.string.ota_progress_type, progress.type))
    }.joinToString(" · ")

    private fun statusLabel(status: Int?): String = when (status) {
        null -> getString(R.string.ota_status_unknown)
        0 -> getString(R.string.ota_status_latest)
        1 -> getString(R.string.ota_status_network_error)
        2 -> getString(R.string.ota_status_downloading)
        3 -> getString(R.string.ota_status_flashing)
        4 -> getString(R.string.ota_status_check_failed)
        5 -> getString(R.string.ota_status_busy)
        6 -> getString(R.string.ota_status_no_power)
        7 -> getString(R.string.ota_status_forbidden)
        else -> getString(R.string.ota_status_failed)
    }

    private fun updateButtons() {
        binding.btnOtaCheck.isEnabled = !busy
        binding.btnOtaRefreshProgress.isEnabled = !busy
        // 已渲染的升级按钮跟随忙碌状态。
        for (index in 0 until binding.containerOtaUpdates.childCount) {
            val child = binding.containerOtaUpdates.getChildAt(index) as? android.view.ViewGroup
            child?.findViewById<android.widget.Button>(R.id.btn_ota_item_upgrade)?.isEnabled = !busy
        }
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.tvOtaResult.text = message
        binding.tvOtaResult.isVisible = message.isNotBlank()
        binding.tvOtaResult.setTextColor(
            MaterialColors.getColor(
                binding.tvOtaResult,
                if (isError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 5_000L
    }
}
