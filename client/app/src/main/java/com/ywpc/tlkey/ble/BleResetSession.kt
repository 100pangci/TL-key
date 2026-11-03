package com.ywpc.tlkey.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ywpc.tlkey.protocol.AuthCodec
import com.ywpc.tlkey.protocol.PacketCodec
import com.ywpc.tlkey.protocol.Protocol85
import com.ywpc.tlkey.protocol.ProtocolEvent
import com.ywpc.tlkey.protocol.ProtocolKeys
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

data class ResetIdentity(
    /** 2a23 System ID 的原始 hex（大写、无分隔符），服务器 bikeBindAgain 的 mac 字段。 */
    val systemIdHex: String,
    /** 401/928/1501/1601/1701/2103/2201 从 2a40 读出的 IMEI。 */
    val imei: String,
    /** V3 车型从 2a41 读出的 code，或 C39 广播 manufacturer data 后半段。 */
    val code: String,
)

/**
 * 官方 BLE_RESET 绑定会话（仅 85 帧车型）。
 *
 * 流程：
 * connect → discover → enable feb6 indication → read 2a23(/2a40/2a41)
 * → （服务器 bikeBindAgain 成功）→ token request → 850A4A10 + main + child
 * → 8503B510 + 01 = 密码写入成功。
 *
 * 每次连接/读取/等待都有超时，任何失败都会 close()。
 */
@SuppressLint("MissingPermission")
class BleResetSession(
    private val context: Context,
    private val address: String,
    private val modelType: Int,
    private val mainPassword: String,
    private val childPassword: String?,
    private val manufacturerData: ByteArray? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: GattClient? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var char2a23: BluetoothGattCharacteristic? = null
    private var char2a40: BluetoothGattCharacteristic? = null
    private var char2a41: BluetoothGattCharacteristic? = null

    private var notifyJob: Job? = null
    private var tokenDeferred: CompletableDeferred<String>? = null
    private var resetDeferred: CompletableDeferred<Boolean>? = null
    private var token: String? = null
    private var closed = false

    private val protocol: Protocol85 =
        ProtocolKeys.forNewProtocol(modelType)?.let(::Protocol85)
            ?: throw BleException.AuthFailed("暂不支持该车型的 BLE 绑定（modelType=$modelType）")

    /** 连接并读取车辆身份。服务器回写 MAC 时必须先拿到这个值。 */
    suspend fun connectAndReadIdentity(): ResetIdentity {
        if (closed) throw BleException.Disconnected()

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: throw BleException.BluetoothDisabled()
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
            gatt.connect(CONNECT_TIMEOUT_MS)
            val services = gatt.discoverServices(DISCOVER_TIMEOUT_MS)

            val service = services.firstOrNull { it.uuid == BleUuids.SERVICE_FEE5 }
                ?: throw BleException.ServiceNotFound()
            val tx = service.getCharacteristic(BleUuids.CHAR_FEB5)
                ?: throw BleException.ServiceNotFound()
            val rx = service.getCharacteristic(BleUuids.CHAR_FEB6)
                ?: throw BleException.ServiceNotFound()
            txCharacteristic = tx
            rxCharacteristic = rx

            val infoService = services.firstOrNull { it.uuid == BleUuids.SERVICE_180A }
                ?: throw BleException.ServiceNotFound()
            char2a23 = infoService.getCharacteristic(BleUuids.CHAR_2A23)
                ?: throw BleException.ServiceNotFound()
            char2a40 = infoService.getCharacteristic(BleUuids.CHAR_2A40)
            char2a41 = infoService.getCharacteristic(BleUuids.CHAR_2A41)

            val properties = rx.properties
            val supportIndication =
                properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val supportNotification =
                properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!supportIndication && !supportNotification) {
                throw BleException.ServiceNotFound()
            }
            // 先订阅通知再启用指示：SharedFlow replay=0，晚订阅会丢掉设备在
            // 指示启用后立即发来的帧（包括可能提前到达的 token 帧）。
            startCollector(gatt)
            gatt.enableNotifications(rx, supportIndication, NOTIFY_TIMEOUT_MS)
            // 官方 setMtu 不等待结果；绑定帧只有 16 字节，MTU 失败不应阻断配对。
            try {
                gatt.requestMtu(512, MTU_TIMEOUT_MS)
            } catch (t: BleException) {
                // 继续使用默认 MTU。
            }

            val systemId = gatt.read(char2a23!!, READ_TIMEOUT_MS).toHexUpper()
            if (systemId.isBlank()) throw BleException.AuthFailed("车辆未返回 System ID")

            val imei = if (needsV3Reads()) readAsciiOrEmpty(gatt, char2a40) else ""
            val code = if (needsV3Reads()) readAsciiOrEmpty(gatt, char2a41) else c39Code()
            if (needsV3Reads() && (imei.isBlank() || code.isBlank())) {
                throw BleException.AuthFailed("车辆未返回 IMEI 或 code，无法完成 V3 绑定")
            }

            return ResetIdentity(systemIdHex = systemId, imei = imei, code = code)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /**
     * 服务器回写成功后调用：发 token 请求，拿到车端 token 后下发
     * `850A4A10 + main + child`（拼 token 后整体 AES 加密）。
     *
     * 官方在 bikeBindAgain 成功回调里才 `writeData("850000002EC97...")`；
     * BLE_RESET 的 onBleReady 本身不写任何帧。
     *
     * @return true = 车端回复 8503B510 01。
     */
    suspend fun resetPassword(): Boolean {
        val gatt = client ?: throw BleException.Disconnected()
        val tx = txCharacteristic ?: throw BleException.ServiceNotFound()
        val child = childPassword?.takeIf { it.isNotBlank() }
            ?: throw BleException.AuthFailed("服务器未下发子密码，无法写入车辆密码")

        val tokenWaiter = CompletableDeferred<String>()
        tokenDeferred = tokenWaiter
        gatt.write(tx, protocol.buildTokenRequest())
        val newToken = try {
            withTimeout(TOKEN_TIMEOUT_MS) { tokenWaiter.await() }
        } catch (t: TimeoutCancellationException) {
            throw BleException.AuthTimeout()
        } finally {
            tokenDeferred = null
        }
        token = newToken

        val resetWaiter = CompletableDeferred<Boolean>()
        resetDeferred = resetWaiter
        val plain = RESET_HEADER + AuthCodec.hex8(mainPassword) + AuthCodec.hex8(child) + newToken
        val key = ProtocolKeys.forNewProtocol(modelType)
            ?: throw BleException.AuthFailed("缺少车型 AES Key")
        gatt.write(tx, PacketCodec.encryptHex(key, plain))

        return try {
            withTimeout(RESET_TIMEOUT_MS) { resetWaiter.await() }
        } catch (t: TimeoutCancellationException) {
            throw BleException.CommandTimeout()
        } finally {
            resetDeferred = null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val error = BleException.Disconnected()
        tokenDeferred?.completeExceptionally(error)
        resetDeferred?.completeExceptionally(error)
        tokenDeferred = null
        resetDeferred = null
        notifyJob?.cancel()
        notifyJob = null
        client?.close()
        client = null
        txCharacteristic = null
        rxCharacteristic = null
        scope.cancel()
    }

    private fun startCollector(gatt: GattClient) {
        notifyJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch {
                gatt.notifications.collect { bytes ->
                    val event = runCatching { protocol.parseIncoming(bytes) }.getOrNull()
                        ?: return@collect
                    when (event) {
                        is ProtocolEvent.Token -> {
                            token = event.token
                            tokenDeferred?.complete(event.token)
                        }

                        is ProtocolEvent.Unknown -> {
                            val raw = event.raw
                            if (raw.startsWith(RESET_REPLY_PREFIX) && raw.length >= 10) {
                                resetDeferred?.complete(raw.substring(8, 10) == "01")
                            }
                        }

                        else -> Unit
                    }
                }
            }
            launch {
                gatt.disconnected.collect {
                    val error = BleException.Disconnected()
                    tokenDeferred?.completeExceptionally(error)
                    resetDeferred?.completeExceptionally(error)
                }
            }
        }
    }

    private fun needsV3Reads(): Boolean = modelType in V3_MODEL_TYPES

    private suspend fun readAsciiOrEmpty(
        gatt: GattClient,
        characteristic: BluetoothGattCharacteristic?,
    ): String {
        if (characteristic == null) return ""
        return try {
            gatt.read(characteristic, READ_TIMEOUT_MS).toHexUpper().hexToAscii()
        } catch (t: BleException) {
            ""
        }
    }

    private fun c39Code(): String {
        if (modelType != 10 && modelType != 14) return ""
        val hex = manufacturerData?.toHexUpper().orEmpty()
        return if (hex.length >= 32) hex.substring(16) else ""
    }

    private fun ByteArray.toHexUpper(): String =
        joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

    private fun String.hexToAscii(): String {
        if (length % 2 != 0) return ""
        val out = StringBuilder(length / 2)
        var index = 0
        while (index + 1 < length) {
            val value = substring(index, index + 2).toIntOrNull(16) ?: return ""
            if (value == 0) break
            out.append(value.toChar())
            index += 2
        }
        return out.toString()
    }

    companion object {
        private const val RESET_HEADER = "850A4A10"
        private const val RESET_REPLY_PREFIX = "8503B510"
        private val V3_MODEL_TYPES = setOf(401, 928, 1501, 1601, 1701, 2103, 2201)

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val DISCOVER_TIMEOUT_MS = 5_000L
        private const val NOTIFY_TIMEOUT_MS = 3_000L
        private const val MTU_TIMEOUT_MS = 1_000L
        private const val READ_TIMEOUT_MS = 3_000L
        private const val TOKEN_TIMEOUT_MS = 7_000L
        private const val RESET_TIMEOUT_MS = 7_000L
    }
}
