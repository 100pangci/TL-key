package com.ywpc.tlkey.protocol

/**
 * 旧版 78 帧。来源：ble/tailg/TailgBleCmd.java、BlueToothTypeConstants.java。
 *
 * token request: 780000002D1A683D48271A18316E471A
 * command:       7803C2 + cmd + 00 + 11111111111111 + token(4B)
 */
class Protocol78(
    private val aesKeyHex: String,
) : VehicleProtocol {

    init {
        require(aesKeyHex.length == 32) { "invalid AES key" }
    }

    override val family: ProtocolFamily = ProtocolFamily.LEGACY_78
    override val requiresLogin: Boolean = false

    override fun buildTokenRequest(): ByteArray =
        PacketCodec.encryptHex(aesKeyHex, TOKEN_REQUEST)

    override fun buildLogin(token: String, credential: BleCredential): ByteArray? = null

    override fun buildCommand(action: ControlAction, token: String): ByteArray {
        requireToken(token)
        val command = when (action) {
            ControlAction.POWER_ON -> "06"
            ControlAction.POWER_OFF -> "07"
            ControlAction.LOCK -> "01"
            ControlAction.UNLOCK -> "02"
            ControlAction.SEAT -> "05"
            ControlAction.FIND -> "08"
        }
        val plain = COMMAND_PREFIX + command + "00" + FILLER + token
        return PacketCodec.encryptHex(aesKeyHex, plain)
    }

    override fun parseIncoming(data: ByteArray): ProtocolEvent {
        val hex = PacketCodec.decryptHex(aesKeyHex, data)
        if (hex.startsWith(TOKEN_REPLY_PREFIX)) {
            return ProtocolEvent.Token(AuthCodec.tokenFrom(hex))
        }

        // 先判成功：断电成功 07FF0B02 也以 07FF 开头。
        val success = when {
            hex.startsWith("060B02") -> ControlAction.POWER_ON
            hex.startsWith("07FF0B02") -> ControlAction.POWER_OFF
            hex.startsWith("010B01") -> ControlAction.LOCK
            hex.startsWith("020B02") -> ControlAction.UNLOCK
            hex.startsWith("0501") -> ControlAction.SEAT
            hex.startsWith("0801") -> ControlAction.FIND
            else -> null
        }
        if (success != null) return ProtocolEvent.Ack(success, true, hex)

        val failure = when {
            hex.startsWith("06FF") -> ControlAction.POWER_ON
            hex.startsWith("07FF") -> ControlAction.POWER_OFF
            hex.startsWith("01FF") -> ControlAction.LOCK
            hex.startsWith("02FF") -> ControlAction.UNLOCK
            hex.startsWith("05FF") -> ControlAction.SEAT
            hex.startsWith("08FF") -> ControlAction.FIND
            else -> null
        }
        if (failure != null) return ProtocolEvent.Ack(failure, false, hex)

        return ProtocolEvent.Unknown(hex)
    }

    private fun requireToken(token: String) {
        if (token.length != 8) throw ProtocolException("BLE token 长度异常")
    }

    companion object {
        const val TOKEN_REQUEST = "780000002D1A683D48271A18316E471A"
        const val TOKEN_REPLY_PREFIX = "78000000"
        private const val COMMAND_PREFIX = "7803C2"
        private const val FILLER = "11111111111111"
    }
}
