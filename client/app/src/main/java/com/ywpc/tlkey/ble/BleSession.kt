package com.ywpc.tlkey.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.protocol.EcuChannel
import com.ywpc.tlkey.protocol.EcuCodec
import com.ywpc.tlkey.protocol.EcuState
import com.ywpc.tlkey.protocol.ProtocolEvent
import com.ywpc.tlkey.protocol.ProtocolFamily
import com.ywpc.tlkey.protocol.VehicleProtocol
import com.ywpc.tlkey.vehicle.Vehicle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 一次 BLE 会话：连接 → 发现服务 → 打开通知 → 取车端 token → 85 帧登录 → 控车。
 *
 * 所有等待都有超时；任何失败都会 close()，确保 GATT 被释放。
 */
@SuppressLint("MissingPermission")
class BleSession(
    private val context: Context,
    private val vehicle: Vehicle,
    private val protocol: VehicleProtocol,
    private val uid: String,
) : VehicleLink, EcuSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var client: GattClient? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var ecuFcc1: BluetoothGattCharacteristic? = null
    private var ecuFbb1: BluetoothGattCharacteristic? = null
    private var notifyJob: Job? = null

    private var authDeferred: CompletableDeferred<Unit>? = null
    private var ackDeferred: CompletableDeferred<Boolean>? = null
    private var expectedAction: ControlAction? = null

    private var token: String? = null
    private var authenticated = false
    private var closed = false

    override val isReady: Boolean
        get() = !closed && authenticated && token != null

    val bleToken: String?
        get() = token

    override suspend fun connectAndAuthenticate(trace: TraceSink) {
        if (closed) throw BleException.Disconnected()
        if (protocol.requiresLogin && uid.isBlank()) {
            throw BleException.AuthFailed("缺少车辆 uid")
        }

        val adapter = bluetoothAdapter()
        if (!adapter.isEnabled) throw BleException.BluetoothDisabled()
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw BleException.PermissionDenied()
        }

        val address = vehicle.bleAddress ?: run {
            val name = vehicle.btName ?: throw BleException.DeviceNotFound()
            BleScanner(context).findByName(name) ?: throw BleException.DeviceNotFound()
        }
        trace.mark("device")

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (t: IllegalArgumentException) {
            throw BleException.DeviceNotFound()
        } catch (t: SecurityException) {
            throw BleException.PermissionDenied()
        }

        val gatt = GattClient(context, device)
        client = gatt

        try {
            gatt.connect(CONNECT_TIMEOUT_MS)
            trace.mark("connect")

            val services = gatt.discoverServices(DISCOVER_TIMEOUT_MS)
            trace.mark("service")
            val service = services.firstOrNull { it.uuid == BleUuids.SERVICE_FEE5 }
                ?: throw BleException.ServiceNotFound()
            val tx = service.getCharacteristic(BleUuids.CHAR_FEB5)
                ?: throw BleException.ServiceNotFound()
            val rx = service.getCharacteristic(BleUuids.CHAR_FEB6)
                ?: throw BleException.ServiceNotFound()
            txCharacteristic = tx
            rxCharacteristic = rx

            services.firstOrNull { it.uuid == BleUuids.SERVICE_FCC0 }?.let { ecu ->
                ecuFcc1 = ecu.getCharacteristic(BleUuids.CHAR_FCC1)
                ecuFbb1 = ecu.getCharacteristic(BleUuids.CHAR_FBB1)
            }

            val properties = rx.properties
            val supportIndication =
                properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val supportNotification =
                properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!supportIndication && !supportNotification) {
                throw BleException.ServiceNotFound()
            }
            gatt.enableNotifications(rx, supportIndication, NOTIFY_TIMEOUT_MS)
            trace.mark("notify")

            // 官方 78 帧在使能通知后等待 2 秒再取 token。
            if (protocol.family == ProtocolFamily.LEGACY_78) {
                delay(LEGACY_NOTIFY_SETTLE_MS)
            }

            gatt.requestMtu(512, MTU_TIMEOUT_MS)
            trace.mark("mtu")

            val deferred = CompletableDeferred<Unit>()
            authDeferred = deferred
            startCollector(gatt)

            gatt.write(tx, protocol.buildTokenRequest())
            trace.mark("token")

            try {
                withTimeout(AUTH_TIMEOUT_MS) { deferred.await() }
            } catch (t: TimeoutCancellationException) {
                throw BleException.AuthTimeout()
            }

            authenticated = true
            trace.mark("auth")
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override suspend fun send(action: ControlAction, trace: TraceSink) {
        val currentToken = token ?: throw BleException.AuthFailed("尚未完成车辆鉴权")
        val gatt = client ?: throw BleException.Disconnected()
        val tx = txCharacteristic ?: throw BleException.ServiceNotFound()
        if (!authenticated) throw BleException.Disconnected()

        val deferred = CompletableDeferred<Boolean>()
        ackDeferred = deferred
        expectedAction = action
        try {
            gatt.write(tx, protocol.buildCommand(action, currentToken))
            trace.mark("command")
            val success = try {
                withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
            } catch (t: TimeoutCancellationException) {
                throw BleException.CommandTimeout()
            }
            trace.mark("ack")
            if (!success) throw BleException.CommandRejected("车辆返回失败")
        } finally {
            ackDeferred = null
            expectedAction = null
        }
    }

    override val hasEcuChannel: Boolean
        get() = !closed && (ecuFcc1 != null || ecuFbb1 != null)

    /**
     * 与官方 readEcu 一致：优先 fcc1，没有 fcc1 时读 fbb1。
     * 两种特征都是原始字节，不经过 85 帧 AES。
     */
    override suspend fun readEcu(): EcuState? {
        val gatt = client ?: throw BleException.Disconnected()
        if (!authenticated) throw BleException.AuthFailed("尚未完成车辆鉴权")

        val fcc1 = ecuFcc1
        if (fcc1 != null) {
            val bytes = gatt.read(fcc1, ECU_TIMEOUT_MS)
            return EcuCodec.parseFcc1(bytes)
                ?: throw BleException.ReadTimeout("ECU fcc1 返回数据不足")
        }
        val fbb1 = ecuFbb1 ?: throw BleException.ServiceNotFound("车辆没有 ECU 特征")
        val bytes = gatt.read(fbb1, ECU_TIMEOUT_MS)
        return EcuCodec.parseFbb1(bytes)
            ?: throw BleException.ReadTimeout("ECU fbb1 返回数据不足")
    }

    override suspend fun writeEcu(state: EcuState) {
        val gatt = client ?: throw BleException.Disconnected()
        if (!authenticated) throw BleException.AuthFailed("尚未完成车辆鉴权")
        val characteristic = when (state.channel) {
            EcuChannel.FCC1 -> ecuFcc1
            EcuChannel.FBB1 -> ecuFbb1
        } ?: throw BleException.ServiceNotFound("车辆没有对应的 ECU 写入特征")
        gatt.write(characteristic, EcuCodec.frame(state), ECU_TIMEOUT_MS)
    }

    override fun close() {
        if (closed) return
        closed = true
        authenticated = false
        val error = BleException.Disconnected()
        authDeferred?.completeExceptionally(error)
        ackDeferred?.completeExceptionally(error)
        authDeferred = null
        ackDeferred = null
        notifyJob?.cancel()
        notifyJob = null
        client?.close()
        client = null
        txCharacteristic = null
        rxCharacteristic = null
        ecuFcc1 = null
        ecuFbb1 = null
        scope.cancel()
    }

    private fun startCollector(gatt: GattClient) {
        notifyJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch {
                gatt.notifications.collect { bytes ->
                    try {
                        handleNotification(bytes)
                    } catch (t: Throwable) {
                        authDeferred?.completeExceptionally(t)
                        ackDeferred?.completeExceptionally(t)
                    }
                }
            }
            launch {
                gatt.disconnected.collect {
                    authenticated = false
                    val error = BleException.Disconnected()
                    authDeferred?.completeExceptionally(error)
                    ackDeferred?.completeExceptionally(error)
                }
            }
        }
    }

    private suspend fun handleNotification(bytes: ByteArray) {
        // 车端可能在 feb6 上发其他通知；无法解密/无法识别时忽略，避免误杀会话。
        val event = try {
            protocol.parseIncoming(bytes)
        } catch (t: Throwable) {
            return
        }

        when (event) {
            is ProtocolEvent.Token -> {
                token = event.token
                if (protocol.requiresLogin) {
                    val credential = vehicle.credential(uid)
                    val frame = try {
                        protocol.buildLogin(event.token, credential)
                    } catch (t: Throwable) {
                        throw BleException.AuthFailed(t.message ?: "车辆登录帧生成失败")
                    } ?: throw BleException.AuthFailed("协议缺少登录帧")
                    val gatt = client ?: throw BleException.Disconnected()
                    val tx = txCharacteristic ?: throw BleException.ServiceNotFound()
                    gatt.write(tx, frame)
                } else {
                    authDeferred?.complete(Unit)
                }
            }

            is ProtocolEvent.AuthSuccess -> authDeferred?.complete(Unit)
            is ProtocolEvent.AuthFailed -> authDeferred?.completeExceptionally(
                BleException.AuthFailed(),
            )

            is ProtocolEvent.Ack -> {
                if (ackDeferred != null && event.action == expectedAction) {
                    ackDeferred?.complete(event.success)
                }
            }

            is ProtocolEvent.Unknown -> Unit
        }
    }

    private fun bluetoothAdapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: throw BleException.BluetoothDisabled()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 1_000L
        private const val DISCOVER_TIMEOUT_MS = 5_000L
        private const val NOTIFY_TIMEOUT_MS = 3_000L
        private const val AUTH_TIMEOUT_MS = 7_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val ECU_TIMEOUT_MS = 3_000L
        private const val LEGACY_NOTIFY_SETTLE_MS = 2_000L
    }
}
