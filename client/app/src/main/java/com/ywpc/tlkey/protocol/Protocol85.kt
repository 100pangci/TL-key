package com.ywpc.tlkey.protocol

/**
 * 新版 85 帧。来源：tlink_ble/TLinkBleManager.java、TailgBleConfig.java。
 *
 * token request: 850000002EC97FA3518DBFE04A6F5B12
 * login:         850A4A11 + pwd(8 hex) + uid(8 hex) + token(8 hex)
 * command:       header(8 hex) + body(16 hex) + token(8 hex)
 */
class Protocol85(
    private val aesKeyHex: String,
) : VehicleProtocol {

    init {
        require(aesKeyHex.length == 32) { "invalid AES key" }
    }

    override val family: ProtocolFamily = ProtocolFamily.NEW_85
    override val requiresLogin: Boolean = true

    override fun buildTokenRequest(): ByteArray =
        PacketCodec.encryptHex(aesKeyHex, TOKEN_REQUEST)

    override fun buildLogin(token: String, credential: BleCredential): ByteArray {
        requireToken(token)
        val password = if (credential.isShared) {
            credential.childPassword?.takeIf { it.isNotBlank() }
                ?: throw ProtocolException("共享车辆缺少子密码")
        } else {
            credential.password.takeIf { it.isNotBlank() }
                ?: throw ProtocolException("缺少车辆蓝牙密码")
        }
        val plain = LOGIN_HEADER + AuthCodec.hex8(password) + AuthCodec.hex8(credential.uid) + token
        return PacketCodec.encryptHex(aesKeyHex, plain)
    }

    override fun buildCommand(action: ControlAction, token: String): ByteArray {
        requireToken(token)
        val plain = when (action) {
            ControlAction.POWER_ON -> COMMAND_POWER_ON
            ControlAction.POWER_OFF -> COMMAND_POWER_OFF
            ControlAction.LOCK -> COMMAND_LOCK
            ControlAction.UNLOCK -> COMMAND_UNLOCK
            ControlAction.FIND -> COMMAND_FIND
            ControlAction.SEAT -> COMMAND_SEAT
        } + token
        return PacketCodec.encryptHex(aesKeyHex, plain)
    }

    override fun parseIncoming(data: ByteArray): ProtocolEvent {
        val hex = PacketCodec.decryptHex(aesKeyHex, data)
        return when {
            hex.startsWith(TOKEN_REPLY_PREFIX) ->
                ProtocolEvent.Token(AuthCodec.tokenFrom(hex))

            hex.startsWith(AUTH_REPLY_PREFIX) ->
                if (AuthCodec.statusFrom(hex) == "01") ProtocolEvent.AuthSuccess
                else ProtocolEvent.AuthFailed

            hex.startsWith(REPLY_POWER_ON) -> ack(ControlAction.POWER_ON, hex)
            hex.startsWith(REPLY_POWER_OFF) -> ack(ControlAction.POWER_OFF, hex)
            hex.startsWith(REPLY_LOCK) -> ack(ControlAction.LOCK, hex)
            hex.startsWith(REPLY_UNLOCK) -> ack(ControlAction.UNLOCK, hex)
            hex.startsWith(REPLY_FIND) -> ack(ControlAction.FIND, hex)
            hex.startsWith(REPLY_SEAT) -> ack(ControlAction.SEAT, hex)

            else -> ProtocolEvent.Unknown(hex)
        }
    }

    private fun ack(action: ControlAction, hex: String): ProtocolEvent =
        ProtocolEvent.Ack(action, AuthCodec.statusFrom(hex) == "01", hex)

    private fun requireToken(token: String) {
        if (token.length != 8) throw ProtocolException("BLE token 长度异常")
    }

    companion object {
        const val TOKEN_REQUEST = "850000002EC97FA3518DBFE04A6F5B12"
        const val TOKEN_REPLY_PREFIX = "85000000"
        const val LOGIN_HEADER = "850A4A11"
        const val AUTH_REPLY_PREFIX = "8503B511"

        private const val COMMAND_POWER_ON = "85034A2200123456789ABCDE"
        private const val COMMAND_POWER_OFF = "85034A2300123456789ABCDE"
        private const val COMMAND_LOCK = "85034A2000123456789ABCDE"
        private const val COMMAND_UNLOCK = "85034A2100123456789ABCDE"
        private const val COMMAND_FIND = "85034A2500123456789ABCDE"
        private const val COMMAND_SEAT = "85034A2400123456789ABCDE"

        private const val REPLY_POWER_ON = "8503B522"
        private const val REPLY_POWER_OFF = "8503B523"
        private const val REPLY_LOCK = "8503B520"
        private const val REPLY_UNLOCK = "8503B521"
        private const val REPLY_FIND = "8503B525"
        private const val REPLY_SEAT = "8503B524"
    }
}
