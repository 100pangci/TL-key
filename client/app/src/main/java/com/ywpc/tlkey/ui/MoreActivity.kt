package com.ywpc.tlkey.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.control.isSuccess
import com.ywpc.tlkey.control.userMessage
import com.ywpc.tlkey.databinding.ActivityMoreBinding
import com.ywpc.tlkey.protocol.ControlAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 二级菜单：主页只保留开锁/上锁、上电/断电、寻车，其余功能集中在这里。
 * 座桶、ECU 驾驶模式、MQTT 云控、OTA、诊断等均为独立入口。
 */
class MoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreBinding
    private lateinit var app: App
    private var busy = false
    private var pendingSeat = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val runSeat = pendingSeat
        pendingSeat = false
        if (!runSeat) return@registerForActivityResult
        if (hasConnectPermission()) {
            performSeat()
        } else {
            showStatus(getString(R.string.ble_permission_needed), isError = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        if (!app.loginManager.hasSession()) {
            openLogin()
            return
        }
        binding = ActivityMoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSeat.setOnClickListener { performSeat() }
        binding.btnCloud.setOnClickListener {
            startActivity(Intent(this, CloudControlActivity::class.java))
        }
        binding.btnOta.setOnClickListener {
            startActivity(Intent(this, OtaActivity::class.java))
        }
        binding.btnEcu.setOnClickListener {
            startActivity(Intent(this, EcuActivity::class.java))
        }
        binding.btnRefresh.setOnClickListener { refreshVehicles() }
        binding.btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }
        setBusy(false)
    }

    private fun performSeat() {
        if (busy) return
        val vehicle = app.controller.cachedVehicle()
        if (vehicle == null) {
            showStatus(getString(R.string.more_vehicle_required), isError = true)
            return
        }
        if (!vehicle.hasBleCredentials) {
            showStatus(getString(R.string.more_vehicle_required), isError = true)
            return
        }
        if (!ensureConnectPermission()) return

        setBusy(true)
        showStatus(getString(R.string.seat), isError = false)
        lifecycleScope.launch {
            try {
                val result = app.controller.control(ControlAction.SEAT)
                val message = "${result.userMessage()} · ${result.trace.totalMs}ms"
                showStatus(message, isError = !result.isSuccess)
                Toast.makeText(this@MoreActivity, result.userMessage(), Toast.LENGTH_SHORT).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun refreshVehicles() {
        if (busy) return
        setBusy(true)
        showStatus(getString(R.string.more_refresh_running), isError = false)
        lifecycleScope.launch {
            try {
                val list = app.loginManager.refreshVehicles()
                showStatus(getString(R.string.more_refresh_done, list.size), isError = false)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showStatus(t.message ?: getString(R.string.car_unknown), isError = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun confirmLogout() {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                app.controller.onLogout()
                app.loginManager.logout()
                openLogin()
            }
            .show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnSeat.isEnabled = !value
        binding.btnCloud.isEnabled = !value
        binding.btnOta.isEnabled = !value
        binding.btnEcu.isEnabled = !value
        binding.btnRefresh.isEnabled = !value
        binding.btnDiagnostics.isEnabled = !value
        binding.btnLogout.isEnabled = !value
        binding.btnBack.isEnabled = !value
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.tvMoreStatus.text = message
        binding.tvMoreStatus.isVisible = message.isNotBlank()
        binding.tvMoreStatus.setTextColor(
            MaterialColors.getColor(
                binding.tvMoreStatus,
                if (isError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

    private fun ensureConnectPermission(): Boolean {
        if (hasConnectPermission()) return true
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            showStatus(getString(R.string.ble_permission_needed), isError = true)
            return false
        }
        pendingSeat = true
        permissionLauncher.launch(missing.toTypedArray())
        return false
    }

    private fun missingPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < 31) {
            val needsScan = app.controller.cachedVehicle()?.bleAddress == null
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
            if (app.controller.cachedVehicle()?.bleAddress == null &&
                !hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            ) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
