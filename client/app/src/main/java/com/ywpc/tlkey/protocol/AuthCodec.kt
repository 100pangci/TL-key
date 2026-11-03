package com.ywpc.tlkey.protocol

import java.util.Locale

object AuthCodec {

    /** 官方：String.format("%08x", Integer.parseInt(value)).toUpperCase() */
    fun hex8(decimal: String): String {
        val value = decimal.toIntOrNull()
            ?: throw ProtocolException("凭据不是十进制整数")
        if (value < 0) throw ProtocolException("凭据不能为负数")
        return String.format(Locale.ROOT, "%08X", value)
    }

    fun tokenFrom(hex: String): String {
        if (hex.length < 16) throw ProtocolException("BLE token 帧长度异常")
        return hex.substring(8, 16)
    }

    fun statusFrom(hex: String): String {
        if (hex.length < 10) throw ProtocolException("BLE 状态帧长度异常")
        return hex.substring(8, 10)
    }
}
