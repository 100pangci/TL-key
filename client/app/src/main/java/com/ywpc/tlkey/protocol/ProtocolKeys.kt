package com.ywpc.tlkey.protocol

import com.ywpc.tlkey.BuildConfig

/**
 * 官方 APK 内的 BLE 互操作协议常量，不是用户凭据。
 *
 * 仓库不保存这些值。构建时通过 Gradle 属性或环境变量注入：
 * - `-Ptlkey.protocol78.key` / `TLKEY_PROTOCOL78_KEY`
 * - `-Ptlkey.protocol85.bb` / `TLKEY_PROTOCOL85_BB`
 * - 以及 jw / jd / ax / hj / xl / yy
 *
 * 未配置时对应协议返回 null，UI 会按“不支持的车型”处理；QGJ（8/283）不使用 AES，不受影响。
 */
object ProtocolKeys {

    private val LEGACY_78: String get() = BuildConfig.PROTOCOL_78_KEY.trim()

    private val BB: String get() = BuildConfig.PROTOCOL_85_BB.trim()
    private val JW: String get() = BuildConfig.PROTOCOL_85_JW.trim()
    private val JD: String get() = BuildConfig.PROTOCOL_85_JD.trim()
    private val AX: String get() = BuildConfig.PROTOCOL_85_AX.trim()
    private val HJ: String get() = BuildConfig.PROTOCOL_85_HJ.trim()
    private val XL: String get() = BuildConfig.PROTOCOL_85_XL.trim()
    private val YY: String get() = BuildConfig.PROTOCOL_85_YY.trim()

    fun legacy78(): String? = LEGACY_78.takeIf(::isValid)

    fun forNewProtocol(modelType: Int): String? = when (modelType) {
        3, 401 -> BB
        10, 14, 928 -> JW
        1501 -> JD
        1601 -> AX
        1701 -> HJ
        2103 -> XL
        2201 -> YY
        else -> null
    }?.takeIf(::isValid)

    private fun isValid(key: String): Boolean =
        key.length == 32 && key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
