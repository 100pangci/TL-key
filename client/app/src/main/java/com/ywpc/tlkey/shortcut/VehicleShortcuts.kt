package com.ywpc.tlkey.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.util.Base64
import androidx.core.content.getSystemService
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.cloud.CloudCommand
import com.ywpc.tlkey.control.displayName
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.vehicle.Vehicle
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 固定到桌面的控车快捷方式。
 *
 * 桌面只拿到一个随机能力令牌，车辆、通道和动作的映射保存在应用私有加密存储中。
 * 这样外部应用即使知道入口 Activity，也无法构造出可执行的快捷方式；退出登录会清除映射。
 */
object VehicleShortcuts {
    const val ACTION_EXECUTE = "com.ywpc.tlkey.action.EXECUTE_SHORTCUT"
    private const val EXTRA_CAPABILITY = "shortcut_capability"
    private const val CAPABILITY_BYTES = 32

    enum class Channel { BLE, CLOUD }

    data class Target(val vehicleId: String, val channel: Channel, val action: String)

    fun pin(context: Context, vehicle: Vehicle, channel: Channel, action: String): PinResult {
        val manager = context.getSystemService<ShortcutManager>() ?: return PinResult.Unsupported
        if (!manager.isRequestPinShortcutSupported) return PinResult.Unsupported
        if (!isValidAction(channel, action)) return PinResult.Invalid

        val app = context.applicationContext as App
        val target = Target(vehicle.id, channel, action)
        // 同一辆车的同一动作重复固定时复用旧令牌，避免已有桌面图标失效、映射无限增长。
        val capability = loadCapabilities(app).entries.firstOrNull { it.value == target }?.key
            ?: newCapability().also { saveCapability(app, it, target) }

        val actionLabel = actionLabel(channel, action)
        val vehicleLabel = vehicle.name.ifBlank { vehicle.carType }.ifBlank {
            context.getString(R.string.car_unknown)
        }
        val label = "$actionLabel · $vehicleLabel".take(100)
        val intent = Intent(context, VehicleShortcutActivity::class.java).apply {
            this.action = ACTION_EXECUTE
            putExtra(EXTRA_CAPABILITY, capability)
        }
        val shortcutId = stableId(vehicle.id, channel, action)
        if (manager.pinnedShortcuts.any { it.id == shortcutId }) return PinResult.AlreadyPinned
        val info = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(label.take(25))
            .setLongLabel(label)
            .setIcon(android.graphics.drawable.Icon.createWithResource(context, iconFor(action)))
            .setIntent(intent)
            .build()
        return if (manager.requestPinShortcut(info, null)) PinResult.Requested else PinResult.Rejected
    }

    fun pinMessage(result: PinResult): String = when (result) {
        PinResult.Requested -> "已提交创建桌面快捷方式请求，请在系统提示中确认"
        PinResult.AlreadyPinned -> "该快捷方式已在桌面"
        PinResult.Unsupported -> "当前启动器不支持添加桌面快捷方式"
        PinResult.Rejected -> "系统未能添加快捷方式，请重试"
        PinResult.Invalid -> "快捷方式动作无效"
    }

    /** 校验令牌并返回固定目标；登录失效、车辆已删除或动作白名单不符时返回 null。 */
    fun resolve(context: Context, intent: Intent?): Target? {
        if (intent?.action != ACTION_EXECUTE) return null
        val capability = runCatching { intent.getStringExtra(EXTRA_CAPABILITY) }.getOrNull()
            ?.takeIf { it.length in 40..64 } ?: return null
        val app = context.applicationContext as App
        if (!app.loginManager.hasSession()) return null
        val target = loadCapabilities(app)[capability] ?: return null
        if (!isValidAction(target.channel, target.action)) return null
        if (app.vehicleRepository.find(target.vehicleId) == null) return null
        return target
    }

    fun actionLabel(channel: Channel, action: String): String {
        val label = when (channel) {
            Channel.BLE -> resolveAction(channel, action)?.displayName().orEmpty()
            Channel.CLOUD -> resolveCloudAction(channel, action)?.label.orEmpty()
        }
        return if (channel == Channel.CLOUD) "云控$label" else label
    }

    fun resolveAction(channel: Channel, action: String): ControlAction? =
        if (channel == Channel.BLE) ControlAction.entries.firstOrNull { it.name == action } else null

    fun resolveCloudAction(channel: Channel, action: String): CloudCommand? =
        if (channel == Channel.CLOUD) {
            CloudCommand.entries.firstOrNull { it.name == action && it != CloudCommand.REFRESH }
        } else {
            null
        }

    private fun isValidAction(channel: Channel, action: String): Boolean = when (channel) {
        Channel.BLE -> resolveAction(channel, action) != null
        Channel.CLOUD -> resolveCloudAction(channel, action) != null
    }

    private fun iconFor(action: String): Int = when (action) {
        "UNLOCK" -> R.drawable.ic_shortcut_unlock
        "LOCK" -> R.drawable.ic_shortcut_lock
        "POWER_ON" -> R.drawable.ic_shortcut_power_on
        "POWER_OFF" -> R.drawable.ic_shortcut_power_off
        "FIND" -> R.drawable.ic_shortcut_find
        "SEAT" -> R.drawable.ic_shortcut_seat
        "MUTE" -> R.drawable.ic_shortcut_mute
        "UNMUTE" -> R.drawable.ic_shortcut_unmute
        else -> R.drawable.ic_shortcut_cloud
    }

    private fun newCapability(): String = ByteArray(CAPABILITY_BYTES)
        .also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    private fun saveCapability(app: App, key: String, target: Target) {
        val entries = loadCapabilities(app) + (key to target)
        app.secureStorage.shortcutCapabilities = JSONArray().apply {
            entries.forEach { (token, value) ->
                put(
                    JSONObject().apply {
                        put("capability", token)
                        put("vehicle", value.vehicleId)
                        put("channel", value.channel.name)
                        put("action", value.action)
                    },
                )
            }
        }.toString()
    }

    private fun loadCapabilities(app: App): Map<String, Target> {
        val array = runCatching { JSONArray(app.secureStorage.shortcutCapabilities.orEmpty()) }.getOrNull()
            ?: return emptyMap()
        return buildMap {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val token = item.optString("capability")
                val vehicle = item.optString("vehicle")
                val channel = runCatching { Channel.valueOf(item.optString("channel")) }.getOrNull()
                val action = item.optString("action")
                if (token.isNotBlank() && vehicle.isNotBlank() && channel != null) {
                    put(token, Target(vehicle, channel, action))
                }
            }
        }
    }

    /** 车辆、通道和动作共同决定 ID；同一按钮重新固定会替换同一个桌面图标。 */
    private fun stableId(vehicleId: String, channel: Channel, action: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$vehicleId\u0000${channel.name}\u0000$action".toByteArray())
        val id = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "vehicle_${id.take(32)}"
    }

    sealed interface PinResult {
        data object Requested : PinResult
        data object AlreadyPinned : PinResult
        data object Unsupported : PinResult
        data object Rejected : PinResult
        data object Invalid : PinResult
    }
}