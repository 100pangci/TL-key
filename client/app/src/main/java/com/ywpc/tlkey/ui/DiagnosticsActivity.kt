package com.ywpc.tlkey.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ywpc.tlkey.App
import com.ywpc.tlkey.BuildConfig
import com.ywpc.tlkey.R
import com.ywpc.tlkey.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var app: App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnCopy.setOnClickListener { copyAll() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.tvDevice.text = deviceInfo()
        binding.tvStatus.text = statusInfo()
        lifecycleScope.launch {
            val last = app.preferences.lastResult()
            binding.tvLast.text = if (last == null) {
                getString(R.string.diag_none)
            } else {
                "${last.first}\n${last.second}"
            }
        }
    }

    private fun deviceInfo(): String {
        val vehicles = app.controller.cachedVehicles()
        val activeId = app.controller.activeVehicleId()
        return buildString {
            appendLine("车辆数: ${vehicles.size}")
            if (vehicles.isEmpty()) appendLine("车辆: 无")
            vehicles.forEach { vehicle ->
                val marker = if (vehicle.id == activeId) "▶" else "·"
                appendLine("$marker ${vehicle.name.ifBlank { vehicle.carType }}")
                appendLine("  车型: ${vehicle.carType.ifBlank { "无" }}")
                appendLine("  modelType: ${vehicle.modelType}")
                appendLine("  协议: ${vehicle.protocolLabel}")
                appendLine("  BLE 地址: ${maskMac(vehicle.bleAddress)}")
                appendLine("  BLE 密码: ${if (vehicle.passwordMain.isBlank()) "未缓存" else "已缓存"}")
                appendLine("  子密码: ${if (vehicle.passwordChildren.isEmpty()) "无" else "${vehicle.passwordChildren.size} 个"}")
                appendLine("  离线凭据: ${if (vehicle.hasBleCredentials) "完整" else "缺失（首次切换需联网）"}")
            }
            append("uid: ${if (app.authRepository.currentUid().isNullOrBlank()) "未缓存" else "已缓存"}")
        }
    }

    private fun statusInfo(): String = buildString {
        appendLine("连接状态: ${app.controller.linkState.value}")
        appendLine("蓝牙: ${bluetoothState()}")
        appendLine("CONNECT: ${permissionState(Manifest.permission.BLUETOOTH_CONNECT)}")
        appendLine("SCAN: ${permissionState(Manifest.permission.BLUETOOTH_SCAN)}")
        appendLine("定位(≤Android 11): ${permissionState(Manifest.permission.ACCESS_FINE_LOCATION)}")
        appendLine("Token: ${if (app.authRepository.hasToken()) "已缓存" else "未登录"}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("App: ${versionName()}")
    }

    private fun bluetoothState(): String {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "不可用"
        if (Build.VERSION.SDK_INT >= 31 &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            return "权限未授予"
        }
        return if (runCatching { adapter.isEnabled }.getOrDefault(false)) "已开启" else "已关闭"
    }

    private fun permissionState(permission: String): String =
        if (hasPermission(permission)) {
            getString(R.string.diag_permission_ok)
        } else {
            getString(R.string.diag_permission_missing)
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun versionName(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun maskMac(mac: String?): String {
        if (mac.isNullOrBlank()) return "无"
        val parts = mac.split(":")
        if (parts.size != 6) return "已缓存"
        return "${parts[0]}:${parts[1]}:**:**:**:${parts[5]}"
    }

    private fun copyAll() {
        val text = buildString {
            appendLine("== 设备与车辆 ==")
            appendLine(binding.tvDevice.text)
            appendLine()
            appendLine("== 运行状态 ==")
            appendLine(binding.tvStatus.text)
            appendLine()
            appendLine("== 最近一次控制 ==")
            append(binding.tvLast.text)
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("TL Key 诊断", text))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }
}
