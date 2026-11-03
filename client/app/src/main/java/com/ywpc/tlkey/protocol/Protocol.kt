package com.ywpc.tlkey.protocol

enum class ProtocolFamily {
    LEGACY_78,
    NEW_85,
    QGJ,
}

data class BleCredential(
    val password: String,
    val uid: String,
    val isShared: Boolean = false,
    val childPassword: String? = null,
)

sealed interface ProtocolEvent {
    data class Token(val token: String) : ProtocolEvent
    data object AuthSuccess : ProtocolEvent
    data object AuthFailed : ProtocolEvent
    data class Ack(
        val action: ControlAction,
        val success: Boolean,
        val raw: String,
    ) : ProtocolEvent

    data class Unknown(val raw: String) : ProtocolEvent
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface VehicleProtocol {
    val family: ProtocolFamily

    /** 78 帧只需取到车端 token；85 帧还需要 pwd + uid 登录。 */
    val requiresLogin: Boolean

    fun buildTokenRequest(): ByteArray

    fun buildLogin(token: String, credential: BleCredential): ByteArray?

    fun buildCommand(action: ControlAction, token: String): ByteArray

    fun parseIncoming(data: ByteArray): ProtocolEvent
}
