package com.ywpc.tlkey.ble

import android.content.Context
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.protocol.EcuState
import com.ywpc.tlkey.vehicle.Vehicle

/**
 * QGJ（modelType 8/283）控车会话。
 *
 * 与 85 帧不同，QGJ 没有车端 token 请求：连接后直接发 ECU_LOGIN（密码 + uid），
 * 成功即可下发 ECU_SET_STATUS。
 */
class QgjSession(
    private val context: Context,
    private val vehicle: Vehicle,
    private val uid: String,
) : VehicleLink, EcuSession {

    private var link: QgjLink? = null
    private var ready = false

    override val isReady: Boolean
        get() = ready && link != null

    override val hasEcuChannel: Boolean
        get() = ready && link?.hasEcuChannel == true

    override suspend fun readEcu(): EcuState? {
        val active = link ?: throw BleException.Disconnected()
        if (!ready) throw BleException.AuthFailed("尚未完成车辆鉴权")
        return active.readEcu()
    }

    override suspend fun writeEcu(state: EcuState) {
        val active = link ?: throw BleException.Disconnected()
        if (!ready) throw BleException.AuthFailed("尚未完成车辆鉴权")
        active.writeEcu(state)
    }

    override suspend fun connectAndAuthenticate(trace: TraceSink) {
        if (uid.isBlank()) throw BleException.AuthFailed("缺少车辆 uid")

        val address = vehicle.bleAddress ?: run {
            val name = vehicle.btName ?: throw BleException.DeviceNotFound()
            BleScanner(context).findByName(name) ?: throw BleException.DeviceNotFound()
        }
        trace.mark("device")

        val credential = vehicle.credential(uid)
        val password = if (credential.isShared) {
            credential.childPassword?.takeIf { it.isNotBlank() }
                ?: throw BleException.AuthFailed("共享车辆缺少子密码")
        } else {
            credential.password.takeIf { it.isNotBlank() }
                ?: throw BleException.AuthFailed("缺少车辆蓝牙密码")
        }

        val fresh = QgjLink(context, address)
        link = fresh
        try {
            fresh.connect()
            trace.mark("connect")
            trace.mark("service")
            fresh.login(password, uid)
            trace.mark("auth")
            ready = true
        } catch (t: Throwable) {
            fresh.close()
            link = null
            ready = false
            throw t
        }
    }

    override suspend fun send(action: ControlAction, trace: TraceSink) {
        val active = link ?: throw BleException.Disconnected()
        if (!ready) throw BleException.AuthFailed("尚未完成车辆鉴权")
        active.control(action)
        trace.mark("command")
        trace.mark("ack")
    }

    /**
     * 283 绑定后的官方流程：BLE_AUTH 登录成功后
     * `connectGpsByUart()` → `gpsActivateSet(ON, 101)`。
     */
    suspend fun activateGps(trace: TraceSink) {
        val active = link ?: throw BleException.Disconnected()
        if (!ready) throw BleException.AuthFailed("尚未完成车辆鉴权")
        active.connectGpsByUart()
        trace.mark("gps-connect")
        active.activateGps()
        trace.mark("gps-activate")
    }

    override fun close() {
        ready = false
        link?.close()
        link = null
    }
}
