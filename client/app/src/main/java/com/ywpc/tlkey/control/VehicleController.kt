package com.ywpc.tlkey.control

import android.content.Context
import com.ywpc.tlkey.auth.AuthRepository
import com.ywpc.tlkey.ble.BleException
import com.ywpc.tlkey.ble.BleSession
import com.ywpc.tlkey.ble.EcuSession
import com.ywpc.tlkey.ble.QgjSession
import com.ywpc.tlkey.ble.VehicleLink
import com.ywpc.tlkey.net.ApiException
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.protocol.EcuState
import com.ywpc.tlkey.protocol.ProtocolFamily
import com.ywpc.tlkey.protocol.ProtocolSelector
import com.ywpc.tlkey.protocol.VehicleProtocol
import com.ywpc.tlkey.storage.AppPreferences
import com.ywpc.tlkey.vehicle.Vehicle
import com.ywpc.tlkey.vehicle.VehicleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LinkState {
    IDLE,
    CONNECTING,
    SEARCHING,
    READY,
    BUSY,
}

/**
 * 控车编排：
 * - 进程存活期间复用已鉴权的 BLE 会话（秒开的关键）。
 * - 缓存有效时完全不联网。
 * - 所有操作串行；失败关闭 GATT；空闲一段时间后主动释放连接。
 */
class VehicleController(
    context: Context,
    private val authRepository: AuthRepository,
    private val vehicleRepository: VehicleRepository,
    private val preferences: AppPreferences,
    private val appScope: CoroutineScope,
) {

    private val context = context.applicationContext
    private val mutex = Mutex()

    private var session: VehicleLink? = null
    private var sessionVehicleId: String? = null
    private var idleJob: Job? = null

    private val _linkState = MutableStateFlow(LinkState.IDLE)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    fun cachedVehicle(): Vehicle? = vehicleRepository.cached()

    fun cachedVehicles(): List<Vehicle> = vehicleRepository.cachedAll()

    fun activeVehicleId(): String? = vehicleRepository.activeId()

    /**
     * 左右滑动切车时调用：只改本地 active id。已缓存 BLE 凭据的车辆不联网；
     * 旧车的 GATT 会话会在下一次控制或显式 closeSession 时释放。
     */
    fun selectVehicle(carId: String): Vehicle? {
        val selected = vehicleRepository.select(carId) ?: return null
        closeSession()
        return selected
    }

    fun hasSession(): Boolean = authRepository.hasToken()

    suspend fun unlock(): ControlResult = control(ControlAction.UNLOCK)

    suspend fun lock(): ControlResult = control(ControlAction.LOCK)

    suspend fun control(action: ControlAction): ControlResult = mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        _linkState.value = LinkState.CONNECTING
        val trace = ControlTrace(action)

        val result = try {
            val vehicle = ensureVehicle()
            val uid = resolveUid(vehicle)
            val protocol = ProtocolSelector.create(vehicle.modelType)
                ?: throw UnsupportedVehicleException(vehicle.modelType)

            val active = obtainSession(vehicle, protocol, uid, trace)
            _linkState.value = LinkState.BUSY
            try {
                active.send(action, trace)
            } catch (t: BleException.Disconnected) {
                // 复用的旧连接可能已被车端断开：释放后重连一次。
                active.close()
                if (session === active) {
                    session = null
                    sessionVehicleId = null
                }
                val fresh = openSession(vehicle, protocol, uid, trace)
                fresh.send(action, trace)
            }

            _linkState.value = LinkState.READY
            ControlResult.Success(action, trace)
        } catch (t: CancellationException) {
            closeSessionLocked()
            throw t
        } catch (t: Throwable) {
            closeSessionLocked()
            t.toControlResult(trace)
        }

        if (result is ControlResult.Success) scheduleIdleClose()
        persist(result)
        result
    }

    /** 不阻塞调用方；真正关闭时会和正在执行的控制串行。 */
    fun closeSession() {
        appScope.launch {
            mutex.withLock { closeSessionLocked() }
        }
    }

    /**
     * 读取 ECU 驾驶模式状态。复用与控车相同的 BLE 会话与空闲关闭策略；
     * 78 帧不支持 ECU，直接返回失败。
     */
    suspend fun readEcu(): EcuResult = mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        _linkState.value = LinkState.CONNECTING
        try {
            val vehicle = ensureVehicle()
            val protocol = ProtocolSelector.create(vehicle.modelType)
                ?: throw UnsupportedVehicleException(vehicle.modelType)
            if (protocol.family == ProtocolFamily.LEGACY_78) throw EcuUnsupportedException()
            val uid = resolveUid(vehicle)
            val trace = ControlTrace(ControlAction.LOCK)
            val link = obtainSession(vehicle, protocol, uid, trace)
            _linkState.value = LinkState.BUSY
            val ecu = link as? EcuSession ?: throw EcuUnsupportedException()
            val state = try {
                ecu.readEcu()
            } catch (t: BleException.Disconnected) {
                link.close()
                if (session === link) {
                    session = null
                    sessionVehicleId = null
                }
                val fresh = openSession(vehicle, protocol, uid, trace)
                (fresh as? EcuSession ?: throw EcuUnsupportedException()).readEcu()
            }
            _linkState.value = LinkState.READY
            scheduleIdleClose()
            if (state == null) {
                EcuResult.Failure("车辆未返回可解析的 ECU 状态")
            } else {
                EcuResult.Success(state, "ECU 状态已读取")
            }
        } catch (t: CancellationException) {
            closeSessionLocked()
            throw t
        } catch (t: Throwable) {
            closeSessionLocked()
            EcuResult.Failure(t.toEcuMessage())
        }
    }

    /**
     * 写入一项 ECU 设置。state 必须来自同一次 [readEcu] 的结果，
     * fbb1 还需要原样保留 value1/value2；写入后官方不会立即回读，UI 应提示已下发。
     */
    suspend fun writeEcu(state: EcuState): EcuResult = mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        _linkState.value = LinkState.CONNECTING
        try {
            val vehicle = ensureVehicle()
            val protocol = ProtocolSelector.create(vehicle.modelType)
                ?: throw UnsupportedVehicleException(vehicle.modelType)
            if (protocol.family == ProtocolFamily.LEGACY_78) throw EcuUnsupportedException()
            val uid = resolveUid(vehicle)
            val trace = ControlTrace(ControlAction.LOCK)
            val link = obtainSession(vehicle, protocol, uid, trace)
            _linkState.value = LinkState.BUSY
            val ecu = link as? EcuSession ?: throw EcuUnsupportedException()
            try {
                ecu.writeEcu(state)
            } catch (t: BleException.Disconnected) {
                link.close()
                if (session === link) {
                    session = null
                    sessionVehicleId = null
                }
                val fresh = openSession(vehicle, protocol, uid, trace)
                (fresh as? EcuSession ?: throw EcuUnsupportedException()).writeEcu(state)
            }
            _linkState.value = LinkState.READY
            scheduleIdleClose()
            EcuResult.Success(state, "设置已下发")
        } catch (t: CancellationException) {
            closeSessionLocked()
            throw t
        } catch (t: Throwable) {
            closeSessionLocked()
            EcuResult.Failure(t.toEcuMessage())
        }
    }

    fun onLogout() = closeSession()

    private suspend fun ensureVehicle(): Vehicle {
        val cached = vehicleRepository.cached()
            ?: throw NoVehicleException()

        val protocol = ProtocolSelector.create(cached.modelType)
        if (protocol != null && cached.hasBleCredentials &&
            (!protocol.requiresLogin || cached.passwordMain.isNotBlank() || cached.passwordChildren.isNotEmpty())
        ) {
            return cached
        }
        // 已知但未支持的车型不值得再走一次网络。
        if (cached.modelType != 0 && protocol == null) {
            throw UnsupportedVehicleException(cached.modelType)
        }

        // 新车第一次被滑到：changeUsingCar + carStatus 拉取 mac/passwordInfo。
        val token = authRepository.getValidToken()
        return vehicleRepository.switchTo(token, cached.id)
    }

    private fun resolveUid(vehicle: Vehicle): String =
        authRepository.currentUid()?.takeIf { it.isNotBlank() }
            ?: vehicle.userId.takeIf { it.isNotBlank() }?.also {
                authRepository.ensureUid(it)
            }
            ?: throw BleException.AuthFailed("缺少车辆 uid，请重新登录")

    private suspend fun obtainSession(
        vehicle: Vehicle,
        protocol: VehicleProtocol,
        uid: String,
        trace: ControlTrace,
    ): VehicleLink {
        val existing = session
        if (existing != null && existing.isReady && sessionVehicleId == vehicle.id) {
            trace.mark("session")
            return existing
        }
        existing?.close()
        session = null
        sessionVehicleId = null
        return openSession(vehicle, protocol, uid, trace)
    }

    private suspend fun openSession(
        vehicle: Vehicle,
        protocol: VehicleProtocol,
        uid: String,
        trace: ControlTrace,
    ): VehicleLink {
        session?.close()
        val fresh: VehicleLink = if (protocol.family == ProtocolFamily.QGJ) {
            QgjSession(
                context = context,
                vehicle = vehicle,
                uid = uid,
                onScanning = { _linkState.value = LinkState.SEARCHING },
                onAddressResolved = { discovered ->
                    // 只在成功鉴权后缓存扫描到的广播地址，避免把偶发匹配写死。
                    val cached = vehicle.btMac?.let(Vehicle::formatMac)
                    if (discovered != cached) {
                        appScope.launch {
                            runCatching {
                                // 扫描期间车辆信息可能已被刷新，基于最新缓存只改 btMac。
                                val latest = vehicleRepository.find(vehicle.id) ?: vehicle
                                vehicleRepository.save(latest.copy(btMac = discovered))
                            }
                        }
                    }
                },
            )
        } else {
            BleSession(context, vehicle, protocol, uid)
        }
        try {
            fresh.connectAndAuthenticate(trace)
        } catch (t: Throwable) {
            fresh.close()
            throw t
        }
        session = fresh
        sessionVehicleId = vehicle.id
        return fresh
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = appScope.launch {
            delay(IDLE_CLOSE_MS)
            mutex.withLock {
                if (_linkState.value != LinkState.BUSY) {
                    closeSessionLocked()
                }
            }
        }
    }

    private fun closeSessionLocked() {
        idleJob?.cancel()
        idleJob = null
        session?.close()
        session = null
        sessionVehicleId = null
        _linkState.value = LinkState.IDLE
    }

    private fun persist(result: ControlResult) {
        appScope.launch {
            runCatching {
                preferences.saveLastResult(
                    action = result.trace.action.displayName(),
                    traceSummary = "${result.userMessage()} · ${result.trace.summary()}",
                )
            }
        }
    }

    private fun Throwable.toControlResult(trace: ControlTrace): ControlResult = when (this) {
        is BleException.BluetoothDisabled -> ControlResult.BluetoothDisabled(trace)
        is BleException.PermissionDenied, is SecurityException ->
            ControlResult.PermissionDenied(trace)

        is BleException.DeviceNotFound -> ControlResult.DeviceNotFound(trace)
        is BleException.ConnectTimeout -> ControlResult.ConnectTimeout(trace)
        is BleException.GattError -> ControlResult.GattError(trace, status)
        is BleException.ServiceNotFound -> ControlResult.ServiceNotFound(trace)
        is BleException.NotificationTimeout -> ControlResult.GattError(trace, -1)
        is BleException.AuthFailed -> ControlResult.AuthFailed(trace, message ?: "车辆鉴权失败")
        is BleException.AuthTimeout -> ControlResult.AuthFailed(trace, "车辆鉴权超时")
        is BleException.CommandRejected ->
            ControlResult.CommandRejected(trace, message ?: "车辆拒绝执行")

        is BleException.CommandTimeout -> ControlResult.CommandTimeout(trace)
        is BleException.Disconnected -> ControlResult.Unknown(trace, "蓝牙连接已断开")
        is BleException.Unknown -> ControlResult.Unknown(trace, message ?: "蓝牙操作失败")
        is ApiException -> if (isTokenExpired) {
            ControlResult.TokenExpired(trace)
        } else {
            ControlResult.NetworkError(trace, message ?: "网络请求失败")
        }

        is NoVehicleException -> ControlResult.Unknown(trace, "请先添加并选择车辆")
        is UnsupportedVehicleException -> ControlResult.Unknown(trace, message ?: "暂不支持该车型")
        else -> ControlResult.Unknown(
            trace,
            message ?: "控制失败（${this::class.java.simpleName}）",
        )
    }

    private class NoVehicleException : Exception("请先添加并选择车辆")

    private class UnsupportedVehicleException(modelType: Int) :
        Exception("暂不支持该车型（modelType=$modelType）")

    private class EcuUnsupportedException : Exception("该车型不支持 ECU 设置")

    private fun Throwable.toEcuMessage(): String = when (this) {
        is BleException.BluetoothDisabled -> "蓝牙未开启"
        is BleException.PermissionDenied, is SecurityException -> "缺少蓝牙权限"
        is BleException.DeviceNotFound -> "未找到车辆"
        is BleException.ConnectTimeout -> "连接车辆超时"
        is BleException.GattError -> "蓝牙通信错误（$status）"
        is BleException.ServiceNotFound -> message ?: "车辆蓝牙服务不匹配"
        is BleException.AuthFailed -> message ?: "车辆鉴权失败"
        is BleException.AuthTimeout -> "车辆鉴权超时"
        is BleException.CommandTimeout, is BleException.ReadTimeout -> "车辆响应超时"
        is BleException.CommandRejected -> message ?: "车辆拒绝执行"
        is BleException.Disconnected -> "蓝牙连接已断开"
        is BleException.Unknown -> message ?: "蓝牙操作失败"
        is ApiException -> message ?: "网络请求失败"
        else -> message ?: "操作失败（${this::class.java.simpleName}）"
    }

    private companion object {
        const val IDLE_CLOSE_MS = 60_000L
    }
}
