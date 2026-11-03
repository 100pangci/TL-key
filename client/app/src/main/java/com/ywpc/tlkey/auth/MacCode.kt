package com.ywpc.tlkey.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * 复现官方 MacCodeUtils / DeviceUtils.getUniqueDeviceId 的设备标识算法。
 * Android 8+ 的 ANDROID_ID 与签名相关，本应用的 macCode 必然与官方 App 不同；
 * 已用本人账号动态验证 app/login 接受该值。
 */
object MacCode {

    private const val PREFS = "device_id"
    private const val KEY = "mac_code"

    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        val value = if (!androidId.isNullOrBlank()) {
            "2" + UUID.nameUUIDFromBytes(androidId.toByteArray(Charsets.UTF_8))
                .toString()
                .replace("-", "")
        } else {
            "9" + UUID.randomUUID().toString().replace("-", "")
        }
        sp.edit().putString(KEY, value).apply()
        return value
    }
}
