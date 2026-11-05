package com.ywpc.tlkey.ble

import android.content.Context
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.protocol.EcuState
import com.ywpc.tlkey.protocol.QgjCodec
import com.ywpc.tlkey.vehicle.Vehicle
import kotlinx.coroutines.CancellationException

/**
 * QGJ（modelType 8/283）控车会话。
 *
 * 服务器 `mac` 是车辆身份 MAC，不一定是可连接的广播地址，连接顺序与官方一致：
 * - 本机已有独立 `btmac` 时先直连（5 秒），连接阶段失败再扫描；
 * - 没有 `btmac` 时直接按身份 MAC 扫描，匹配到广播地址后再连接；
 * - Harmony 的 System ID 只能连接后读 180a/2a23，扫描阶段按名称收集候选并逐个校验。
 *
 * 鉴权成功后才把扫描到的广播地址回写为 `btmac`，避免缓存偶然匹配的地址。
 *
 * 与 85 帧不同，QGJ 没有车端 token 请求：连接后直接发 ECU_LOGIN（密码 + uid），
 * 成功即可下发 ECU_SET_STATUS。
 */
class QgjSession(
    private val context: Context,
    private val vehicle: Vehicle,
    private val uid: String,
    private val onScanning: () -> Unit = {},
    private val onAddressResolved: (String) -> Unit = {},
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

        val password = resolvePassword()
        val storedAddress = vehicle.btMac?.let(Vehicle::formatMac)
        val identityMac = QgjCodec.normalizeIdentity(vehicle.mac)
        trace.mark("device")
        var failure: Throwable? = null

        // 官方绑定/扫描拿到过广播地址时，先直连，避免每次控车都等待扫描。
        if (storedAddress != null) {
            try {
                connectAndLogin(storedAddress, password, trace, FAST_CONNECT_TIMEOUT_MS)
                return
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                failure = t
                if (!t.canRetryWithScan()) throw t
            }
        }

        if (identityMac != null) {
            onScanning()
            val outcome = try {
                BleScanner(context).scanQgj(identityMac, vehicle.btName)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (t is BleException.PermissionDenied || t is BleException.BluetoothDisabled) {
                    throw t
                }
                failure = t
                null
            }
            trace.mark("scan")

            outcome?.identityMatch?.let { match ->
                try {
                    connectAndLogin(match.address, password, trace, CONNECT_TIMEOUT_MS)
                    onAddressResolved(match.address)
                    return
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    failure = t
                }
            }

            // Harmony 广播里没有 System ID，只能连接候选后读 2a23 校验，校验通过再登录。
            for (candidate in outcome?.harmonyCandidates.orEmpty()) {
                if (candidate.address == storedAddress) continue
                try {
                    connectHarmonyAndLogin(candidate.address, identityMac, password, trace)
                    onAddressResolved(candidate.address)
                    return
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    failure = t
                }
            }
        }

        // 兼容旧路径：没有独立 btmac 时，最后再直连服务器 mac / 按广播名扫描。
        if (storedAddress == null) {
            val fallbackAddress = Vehicle.formatMac(vehicle.mac)
                ?: vehicle.btName?.let { BleScanner(context).findByName(it) }
            if (fallbackAddress != null) {
                try {
                    connectAndLogin(fallbackAddress, password, trace, FAST_CONNECT_TIMEOUT_MS)
                    return
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    failure = t
                }
            }
        }

        throw failure ?: BleException.DeviceNotFound()
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

    private fun resolvePassword(): String {
        val credential = vehicle.credential(uid)
        return if (credential.isShared) {
            credential.childPassword?.takeIf { it.isNotBlank() }
                ?: throw BleException.AuthFailed("共享车辆缺少子密码")
        } else {
            credential.password.takeIf { it.isNotBlank() }
                ?: throw BleException.AuthFailed("缺少车辆蓝牙密码")
        }
    }

    private suspend fun connectAndLogin(
        address: String,
        password: String,
        trace: TraceSink,
        timeoutMs: Long,
    ) {
        val fresh = QgjLink(context, address)
        try {
            fresh.connect(timeoutMs)
            trace.mark("connect")
            trace.mark("service")
            fresh.login(password, uid)
            trace.mark("auth")
            link = fresh
            ready = true
        } catch (t: Throwable) {
            fresh.close()
            throw t
        }
    }

    private suspend fun connectHarmonyAndLogin(
        address: String,
        identityMac: String,
        password: String,
        trace: TraceSink,
    ) {
        val fresh = QgjLink(context, address)
        try {
            fresh.connect(CONNECT_TIMEOUT_MS)
            val systemId = QgjCodec.normalizeIdentity(fresh.readSystemId())
            if (systemId != identityMac) {
                throw BleException.AuthFailed("车辆身份不匹配")
            }
            trace.mark("connect")
            trace.mark("service")
            fresh.login(password, uid)
            trace.mark("auth")
            link = fresh
            ready = true
        } catch (t: Throwable) {
            fresh.close()
            throw t
        }
    }

    /** 只有连接阶段失败才值得换扫描地址重试；鉴权失败说明车已连上，不应重扫。 */
    private fun Throwable.canRetryWithScan(): Boolean = when (this) {
        is BleException.ConnectTimeout, is BleException.DeviceNotFound -> true
        is BleException.GattError ->
            status == GATT_CONN_TIMEOUT ||
                status == GATT_CONN_LMP_TIMEOUT ||
                status == GATT_CONN_FAIL_ESTABLISH ||
                status == GATT_ERROR

        else -> false
    }

    private companion object {
        const val FAST_CONNECT_TIMEOUT_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 10_000L

        // BluetoothGatt 未公开这些常量的稳定符号，按 Android 定义固定取值。
        const val GATT_CONN_TIMEOUT = 8
        const val GATT_CONN_LMP_TIMEOUT = 34
        const val GATT_CONN_FAIL_ESTABLISH = 62
        const val GATT_ERROR = 133
    }
}
