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
import com.ywpc.tlkey.control.userMessage
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * 桌面快捷方式入口：不进入主界面，直接执行开锁/上锁。
 */
class VehicleShortcutActivity : AppCompatActivity() {

    private lateinit var app: App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App

        val action = when (intent?.action) {
            ACTION_UNLOCK -> ControlAction.UNLOCK
            ACTION_LOCK -> ControlAction.LOCK
            else -> {
                finish()
                return
            }
        }

        if (!app.loginManager.hasSession()) {
            openMain()
            return
        }
        if (!hasConnectPermission()) {
            Toast.makeText(this, "请先打开 TL Key 授权蓝牙", Toast.LENGTH_LONG).show()
            openMain()
            return
        }

        lifecycleScope.launch {
            val result = app.controller.control(action)
            Toast.makeText(
                this@VehicleShortcutActivity,
                result.userMessage(),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_UNLOCK = "com.ywpc.tlkey.action.UNLOCK"
        const val ACTION_LOCK = "com.ywpc.tlkey.action.LOCK"
    }
}
