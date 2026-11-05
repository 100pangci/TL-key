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
import com.ywpc.tlkey.protocol.QgjCodec
import com.ywpc.tlkey.protocol.QgjFrame
import com.ywpc.tlkey.protocol.QgjVariant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * QGJ（Kuyi SDK）的 GATT 通道。
 *
 * 连接后按广播/服务自动选择两代 ECU：
 * - V1：service ffe1，write fee2，indicate fee3（对应 `com.kuyi.g.r0`）
 * - V2：service feb0，write feb1，indicate feb2（对应 `com.kuyi.h.f1`）
 *
 * 帧格式见 [QgjCodec]。GPS 通道（fe01/fe02/fe03）只用于 283 绑定读取 IMEI/code。
 */
@SuppressLint("MissingPermission")
class QgjLink(
    private val context: Context,
    private val address: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = HashMap<Int, CompletableDeferred<QgjFrame>>()

    private var client: GattClient? = null
    private var variant: QgjVariant? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var gpsTx: BluetoothGattCharacteristic? = null
    private var gpsRx: BluetoothGattCharacteristic? = null
    private var ecuFcc1: BluetoothGattCharacteristic? = null
    private var ecuFbb1: BluetoothGattCharacteristic? = null
    private var systemIdChar: BluetoothGattCharacteristic? = null
    private var notifyJob: Job? = null
    private var activeCommandId: Int? = null
    private var closed = false

    val qgjVariant: QgjVariant?
        get() = variant

    val hasGpsChannel: Boolean
        get() = gpsTx != null && gpsRx != null

    suspend fun connect(timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        if (closed) throw BleException.Disconnected()
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
            gatt.connect(timeoutMs)
            val services = gatt.discoverServices(DISCOVER_TIMEOUT_MS)

            val v2 = services.firstOrNull { it.uuid == BleUuids.SERVICE_QGJ_V2 }
            val v1 = services.firstOrNull { it.uuid == BleUuids.SERVICE_QGJ_V1 }
            when {
                v2 != null -> {
                    variant = QgjVariant.V2
                    tx = v2.getCharacteristic(BleUuids.CHAR_QGJ_V2_TX)
                    rx = v2.getCharacteristic(BleUuids.CHAR_QGJ_V2_RX)
                }

                v1 != null -> {
                    variant = QgjVariant.V1
                    tx = v1.getCharacteristic(BleUuids.CHAR_QGJ_V1_TX)
                    rx = v1.getCharacteristic(BleUuids.CHAR_QGJ_V1_RX)
                }

                else -> throw BleException.ServiceNotFound()
            }
            val txChar = tx ?: throw BleException.ServiceNotFound()
            val rxChar = rx ?: throw BleException.ServiceNotFound()
            if (txChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                txChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0
            ) {
                throw BleException.ServiceNotFound()
            }

            startCollector(gatt)
            gatt.enableNotifications(
                rxChar,
                indication = rxChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                timeoutMs = NOTIFY_TIMEOUT_MS,
            )

            services.firstOrNull { it.uuid == BleUuids.SERVICE_180A }?.let { info ->
                systemIdChar = info.getCharacteristic(BleUuids.CHAR_2A23)
            }

            // 与 85 帧相同：ECU 通过 fcc0 服务的 fcc1/fbb1 原始特征读写。
            services.firstOrNull { it.uuid == BleUuids.SERVICE_FCC0 }?.let { ecu ->
                ecuFcc1 = ecu.getCharacteristic(BleUuids.CHAR_FCC1)
                ecuFbb1 = ecu.getCharacteristic(BleUuids.CHAR_FBB1)
            }

            services.firstOrNull { it.uuid == BleUuids.SERVICE_QGJ_GPS }?.let { gps ->
                val txCandidate = gps.getCharacteristic(BleUuids.CHAR_QGJ_GPS_TX)
                val rxCandidate = gps.getCharacteristic(BleUuids.CHAR_QGJ_GPS_RX)
                if (txCandidate != null && rxCandidate != null) {
                    gpsTx = txCandidate
                    gpsRx = rxCandidate
                    gatt.enableNotifications(
                        rxCandidate,
                        indication = rxCandidate.properties and
                            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                        timeoutMs = NOTIFY_TIMEOUT_MS,
                    )
                }
            }

            // Kuyi 先请求 515，失败也不影响后续命令。
            runCatching { gatt.requestMtu(512, MTU_TIMEOUT_MS) }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /** 登录：V1 只发密码；V2 发密码 + uid。 */
    suspend fun login(password: String, uid: String) {
        val current = variant ?: throw BleException.Disconnected()
        val command = if (current == QgjVariant.V1) {
            QgjCodec.CMD_V1_LOGIN
        } else {
            QgjCodec.CMD_V2_LOGIN
        }
        val payload = QgjCodec.loginPayload(password, uid, current)
        val frame = request(current, command, payload, AUTH_TIMEOUT_MS)
        if (!QgjCodec.statusOk(frame.payload)) {
            throw BleException.AuthFailed("QGJ 车辆拒绝登录")
        }
    }

    suspend fun control(action: ControlAction) {
        val current = variant ?: throw BleException.Disconnected()
        val payload = QgjCodec.controlPayload(action)
        when (current) {
            // V1 的 ECU_SET_STATUS 是 no-reply 命令，写成功即视为已下发。
            QgjVariant.V1 -> write(QgjCodec.CMD_V1_SET_STATUS, payload, QgjVariant.V1)
            QgjVariant.V2 -> {
                val frame = request(
                    QgjVariant.V2,
                    QgjCodec.CMD_V2_SET_STATUS,
                    payload,
                    COMMAND_TIMEOUT_MS,
                )
                if (!QgjCodec.statusOk(frame.payload)) {
                    throw BleException.CommandRejected("QGJ 车辆拒绝执行")
                }
            }

            QgjVariant.GPS -> throw BleException.ServiceNotFound()
        }
    }

    val hasEcuChannel: Boolean
        get() = !closed && (ecuFcc1 != null || ecuFbb1 != null)

    suspend fun readEcu(): EcuState? {
        val gatt = client ?: throw BleException.Disconnected()
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

    suspend fun writeEcu(state: EcuState) {
        val gatt = client ?: throw BleException.Disconnected()
        val characteristic = when (state.channel) {
            EcuChannel.FCC1 -> ecuFcc1
            EcuChannel.FBB1 -> ecuFbb1
        } ?: throw BleException.ServiceNotFound("车辆没有对应的 ECU 写入特征")
        gatt.write(characteristic, EcuCodec.frame(state), ECU_TIMEOUT_MS)
    }

    /**
     * 绑定写密码。V1 最多 6 个密码；V2 使用 main+child，legacy 设备不带名称前缀。
     */
    suspend fun bind(
        mainPassword: String,
        childPasswords: List<String>,
        legacyV2: Boolean,
        namePrefix: String = "TL",
    ) {
        val current = variant ?: throw BleException.Disconnected()
        if (current == QgjVariant.GPS) throw BleException.ServiceNotFound()
        val payload = QgjCodec.bindPayload(
            mainPassword = mainPassword,
            childPasswords = childPasswords,
            variant = current,
            legacyV2 = legacyV2,
            namePrefix = namePrefix,
        )
        val command = if (current == QgjVariant.V1) {
            QgjCodec.CMD_V1_BIND
        } else {
            QgjCodec.CMD_V2_BIND
        }
        val frame = request(current, command, payload, BIND_TIMEOUT_MS)
        if (!QgjCodec.statusOk(frame.payload)) {
            throw BleException.CommandRejected("QGJ 车辆拒绝写入绑定密码")
        }
    }

    /** 283 绑定前读取 GPS IMEI 与自定义数据 code。 */
    suspend fun readGpsIdentity(): Pair<String, String> {
        if (!hasGpsChannel) {
            throw BleException.ServiceNotFound()
        }
        val imeiFrame = request(
            QgjVariant.GPS,
            QgjCodec.CMD_GPS_QUERY_IMEI,
            ByteArray(0),
            GPS_TIMEOUT_MS,
        )
        val imei = QgjCodec.decodeGpsString(imeiFrame.payload)
        if (imei.isBlank()) throw BleException.AuthFailed("车辆未返回 GPS IMEI")

        val codeFrame = request(
            QgjVariant.GPS,
            QgjCodec.CMD_GPS_CUSTOM_DATA_GET,
            QgjCodec.gpsStringPayload("code"),
            GPS_TIMEOUT_MS,
        )
        val code = QgjCodec.decodeGpsString(codeFrame.payload)
        if (code.isBlank()) throw BleException.AuthFailed("车辆未返回 GPS code")
        return imei to code
    }

    /**
     * Harmony 设备的身份 MAC 来自标准 Device Information Service 的 2a23（Kuyi 的 SID），
     * 只有连接后才能读取；非 Harmony 设备用广播 manufacturer data。
     */
    suspend fun readSystemId(): String {
        val gatt = client ?: throw BleException.Disconnected()
        val characteristic = systemIdChar
            ?: throw BleException.ServiceNotFound()
        val raw = gatt.read(characteristic, READ_TIMEOUT_MS)
        val hex = raw.joinToString("") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
        }
        if (hex.isBlank()) throw BleException.AuthFailed("车辆未返回 System ID")
        return hex
    }

    /** 283 绑定后：让 ECU 连接本地 GPS 模块（`connectGpsByUart`，无 payload）。 */
    suspend fun connectGpsByUart() {
        val current = variant ?: throw BleException.Disconnected()
        if (current != QgjVariant.V2) throw BleException.ServiceNotFound()
        val frame = request(
            QgjVariant.V2,
            QgjCodec.CMD_ECU_GPS_CONNECT_LOCAL,
            ByteArray(0),
            GPS_TIMEOUT_MS,
        )
        if (!QgjCodec.statusOk(frame.payload)) {
            throw BleException.CommandRejected("QGJ 车辆未连接 GPS 模块")
        }
    }

    /** 283 绑定后：`gpsActivateSet(ON, 101)`。 */
    suspend fun activateGps() {
        val current = variant ?: throw BleException.Disconnected()
        if (current != QgjVariant.V2) throw BleException.ServiceNotFound()
        val frame = request(
            QgjVariant.V2,
            QgjCodec.CMD_GPS_SET_STATUS,
            QgjCodec.gpsActivatePayload(),
            GPS_TIMEOUT_MS,
        )
        if (!QgjCodec.statusOk(frame.payload)) {
            throw BleException.CommandRejected("QGJ 车辆拒绝激活 GPS")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        notifyJob?.cancel()
        notifyJob = null
        val error = BleException.Disconnected()
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        client?.close()
        client = null
        tx = null
        rx = null
        gpsTx = null
        gpsRx = null
        systemIdChar = null
        variant = null
        scope.cancel()
    }

    private suspend fun request(
        requestVariant: QgjVariant,
        commandId: Int,
        payload: ByteArray,
        timeoutMs: Long,
    ): QgjFrame {
        val gatt = client ?: throw BleException.Disconnected()
        val target = if (requestVariant == QgjVariant.GPS) {
            gpsTx ?: throw BleException.ServiceNotFound()
        } else {
            tx ?: throw BleException.ServiceNotFound()
        }
        val deferred = CompletableDeferred<QgjFrame>()
        synchronized(pending) {
            pending[commandId]?.completeExceptionally(
                BleException.CommandTimeout(),
            )
            pending[commandId] = deferred
            activeCommandId = commandId
        }
        try {
            gatt.write(target, QgjCodec.frame(requestVariant, commandId, payload), COMMAND_TIMEOUT_MS)
            val frame = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (t: TimeoutCancellationException) {
                throw BleException.CommandTimeout()
            }
            frame.errorCode?.let { throw BleException.CommandRejected("QGJ 错误码 $it") }
            return frame
        } finally {
            synchronized(pending) {
                pending.remove(commandId, deferred)
                if (activeCommandId == commandId) activeCommandId = null
            }
        }
    }

    private suspend fun write(
        commandId: Int,
        payload: ByteArray,
        requestVariant: QgjVariant,
    ) {
        val gatt = client ?: throw BleException.Disconnected()
        val target = tx ?: throw BleException.ServiceNotFound()
        gatt.write(
            target,
            QgjCodec.frame(requestVariant, commandId, payload),
            COMMAND_TIMEOUT_MS,
        )
    }

    private fun startCollector(gatt: GattClient) {
        notifyJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch {
                gatt.notifications.collect { bytes -> handleNotification(bytes) }
            }
            launch {
                gatt.disconnected.collect {
                    val error = BleException.Disconnected()
                    synchronized(pending) {
                        pending.values.forEach { it.completeExceptionally(error) }
                        pending.clear()
                    }
                }
            }
        }
    }

    private fun handleNotification(bytes: ByteArray) {
        val frame = QgjCodec.parse(bytes) ?: return
        val commandId = frame.commandId
        synchronized(pending) {
            val deferred = pending.remove(commandId)
                ?: activeCommandId?.let { pending[it] }
                ?: return
            if (frame.commandId < 0) {
                activeCommandId?.let { pending.remove(it) }
            }
            deferred.complete(frame)
        }
    }

    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: throw BleException.BluetoothDisabled()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val DISCOVER_TIMEOUT_MS = 5_000L
        const val NOTIFY_TIMEOUT_MS = 3_000L
        const val MTU_TIMEOUT_MS = 1_000L
        const val AUTH_TIMEOUT_MS = 5_000L
        const val COMMAND_TIMEOUT_MS = 3_000L
        const val BIND_TIMEOUT_MS = 5_000L
        const val GPS_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 3_000L
        const val ECU_TIMEOUT_MS = 3_000L
    }
}
