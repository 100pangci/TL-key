package com.ywpc.tlkey.ble

import com.ywpc.tlkey.protocol.EcuState

/**
 * ECU 驾驶模式通道。85 帧与 QGJ 都通过 fcc0 服务的 fcc1/fbb1 原始特征读写；
 * 78 帧不支持。
 */
interface EcuSession {
    val hasEcuChannel: Boolean

    /** 读取当前状态与能力位；无法读取时返回 null。 */
    suspend fun readEcu(): EcuState?

    /** 写入已经改好的状态帧（fcc1/fbb1 由 [EcuState.channel] 决定）。 */
    suspend fun writeEcu(state: EcuState)
}
