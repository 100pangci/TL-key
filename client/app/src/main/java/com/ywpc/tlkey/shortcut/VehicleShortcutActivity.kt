package com.ywpc.tlkey.shortcut

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ywpc.tlkey.App
import com.ywpc.tlkey.cloud.CloudCommand
import com.ywpc.tlkey.cloud.MqttCloudClient
import com.ywpc.tlkey.cloud.MqttEndpointResult
import com.ywpc.tlkey.cloud.MqttEndpoints
import com.ywpc.tlkey.control.userMessage
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面快捷方式入口：不进入主界面，直接执行固定车辆的动作。
 * 新快捷方式通过能力令牌解析目标；旧版静态 Unlock / Lock 快捷方式继续控制当前车辆。
 */
class VehicleShortcutActivity : AppCompatActivity() {

    private lateinit var app: App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        if (savedInstanceState != null) {
            finish()
            return
        }
        if (!app.loginManager.hasSession()) {
            openMain()
            return
        }

        if (intent?.action == VehicleShortcuts.ACTION_EXECUTE) {
            val target = VehicleShortcuts.resolve(this, intent)
            if (target == null) {
                showMessage("快捷方式已失效，请重新添加")
                return
            }
            when (target.channel) {
                VehicleShortcuts.Channel.BLE -> {
                    val vehicle = app.vehicleRepository.find(target.vehicleId)
                    val action = VehicleShortcuts.resolveAction(target.channel, target.action)
                    if (vehicle == null || action == null || !vehicle.hasBleCredentials) {
                        showMessage("目标车辆或蓝牙凭据不存在，请重新添加快捷方式")
                        return
                    }
                    if (!hasConnectPermission()) {
                        showMessage("请先打开 TL Key 并授权蓝牙后重试")
                        openMain()
                        return
                    }
                    lifecycleScope.launch {
                        val result = app.controller.control(action, vehicle.id)
                        showMessage(result.userMessage())
                    }
                }
                VehicleShortcuts.Channel.CLOUD -> {
                    val vehicle = app.vehicleRepository.find(target.vehicleId)
                    val command = VehicleShortcuts.resolveCloudAction(target.channel, target.action)
                    if (vehicle == null || command == null) {
                        showMessage("目标车辆或云控动作不存在，请重新添加快捷方式")
                        return
                    }
                    executeCloud(vehicle.id, command)
                }
            }
            return
        }

        // 旧版静态快捷方式保持原行为：控制当前选中的车辆。
        val action = when (intent?.action) {
            ACTION_UNLOCK -> ControlAction.UNLOCK
            ACTION_LOCK -> ControlAction.LOCK
            else -> null
        }
        if (action == null) {
            finish()
            return
        }
        if (!hasConnectPermission()) {
            showMessage("请先打开 TL Key 并授权蓝牙后重试")
            openMain()
            return
        }
        lifecycleScope.launch { showMessage(app.controller.control(action).userMessage()) }
    }

    private fun executeCloud(vehicleId: String, command: CloudCommand) {
        val vehicle = app.vehicleRepository.find(vehicleId)
        if (vehicle == null) {
            showMessage("目标车辆不存在，请重新添加快捷方式")
            return
        }
        val uid = vehicle.userId.takeIf(String::isNotBlank)
            ?: app.authRepository.currentUid().orEmpty()
        val endpointResult = MqttEndpoints.resolve(vehicle, uid)
        if (endpointResult !is MqttEndpointResult.Ready) {
            showMessage("该车云控信息不完整，请在应用中刷新车辆信息")
            return
        }
        val client = MqttCloudClient()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { client.connect(endpointResult.endpoint) }
                client.send(command)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { client.disconnect() }
            }
            showMessage(result.message)
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_UNLOCK = "com.ywpc.tlkey.action.UNLOCK"
        const val ACTION_LOCK = "com.ywpc.tlkey.action.LOCK"
    }
}
