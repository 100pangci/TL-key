package com.ywpc.tlkey.tile

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.control.userMessage
import com.ywpc.tlkey.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快捷设置磁贴：单击开锁，长按进入 App。
 * 车端没有可靠的锁状态回读，因此磁贴始终显示 INACTIVE，不假装知道当前是否已锁。
 */
@SuppressLint("MissingPermission", "StartActivityAndCollapseDeprecated")
class VehicleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(getString(R.string.tile_subtitle))
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(getString(R.string.tile_subtitle))
    }

    override fun onClick() {
        val app = applicationContext as App
        if (!app.loginManager.hasSession() || !hasConnectPermission()) {
            openApp()
            return
        }
        updateTile("正在开锁…")
        app.appScope.launch {
            val result = app.controller.unlock()
            withContext(Dispatchers.Main) {
                updateTile(result.userMessage())
                Toast.makeText(
                    applicationContext,
                    result.userMessage(),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun updateTile(text: String) {
        qsTile?.apply {
            label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= 29) {
                subtitle = text
            }
            state = Tile.STATE_INACTIVE
            icon = Icon.createWithResource(this@VehicleTileService, R.drawable.ic_unlock)
            updateTile()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
}
