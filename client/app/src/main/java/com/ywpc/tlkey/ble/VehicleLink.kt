package com.ywpc.tlkey.ble

import com.ywpc.tlkey.protocol.ControlAction

/**
 * 控车会话的公共接口。78/85 帧由 [BleSession] 实现，QGJ 由 [QgjSession] 实现；
 * VehicleController 只依赖本接口，便于复用空闲关闭、断线重连和控制串行化逻辑。
 */
interface VehicleLink {
    val isReady: Boolean

    suspend fun connectAndAuthenticate(trace: TraceSink)

    suspend fun send(action: ControlAction, trace: TraceSink)

    fun close()
}
