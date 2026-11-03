package com.ywpc.tlkey.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 官方 BLE 帧统一为 AES/ECB/NoPadding，明文与密文都通过 hex 表达。 */
object PacketCodec {

    fun encryptHex(keyHex: String, plainHex: String): ByteArray {
        val key = HexCodec.decode(keyHex)
        val plain = HexCodec.decode(plainHex)
        require(plain.size % 16 == 0) { "plaintext must be 16-byte aligned" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    fun decryptHex(keyHex: String, cipherBytes: ByteArray): String {
        require(cipherBytes.size % 16 == 0) { "ciphertext must be 16-byte aligned" }
        val key = HexCodec.decode(keyHex)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return HexCodec.encode(cipher.doFinal(cipherBytes))
    }
}

object HexCodec {

    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            out[index * 2] = DIGITS[value ushr 4]
            out[index * 2 + 1] = DIGITS[value and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex string" }
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
