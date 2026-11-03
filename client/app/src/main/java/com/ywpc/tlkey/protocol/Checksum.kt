package com.ywpc.tlkey.protocol

import java.util.Locale

/**
 * 3.6.2 的 GATT 2002 ECU OTA 使用 sum(payload) & 0xFF。
 * 日常控车帧不带 checksum，这里只作为协议工具与单测覆盖。
 */
object Checksum {

    fun sum8(data: ByteArray): Int {
        var sum = 0
        for (byte in data) sum += byte.toInt() and 0xFF
        return sum and 0xFF
    }

    fun sum8Hex(data: ByteArray): String =
        String.format(Locale.ROOT, "%02X", sum8(data))
}
