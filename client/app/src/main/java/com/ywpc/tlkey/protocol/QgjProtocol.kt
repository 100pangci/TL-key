package com.ywpc.tlkey.protocol

/**
 * QGJ（8/283）协议族标记。
 *
 * QGJ 的连接与帧交换由 [com.ywpc.tlkey.ble.QgjSession] 完成：它不是 85 帧的
 * token + AES 登录流程，而是 Kuyi 的 A5/A7 帧 + 密码/uid 登录。这里只让
 * ProtocolSelector 能表达“该车型属于 QGJ”，避免控制层再写一套分支判断。
 */
class QgjProtocol : VehicleProtocol {

    override val family: ProtocolFamily = ProtocolFamily.QGJ
    override val requiresLogin: Boolean = true

    override fun buildTokenRequest(): ByteArray =
        throw ProtocolException("QGJ 不使用 BLE token")

    override fun buildLogin(token: String, credential: BleCredential): ByteArray? =
        throw ProtocolException("QGJ 登录由 QgjSession 处理")

    override fun buildCommand(action: ControlAction, token: String): ByteArray =
        throw ProtocolException("QGJ 控制帧由 QgjSession 处理")

    override fun parseIncoming(data: ByteArray): ProtocolEvent =
        ProtocolEvent.Unknown("QGJ")
}
