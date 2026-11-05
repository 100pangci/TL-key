package com.ywpc.tlkey.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ywpc.tlkey.protocol.QgjCodec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 只有车辆没有已知 MAC 时才允许扫描。目标车辆 85 帧直接 getRemoteDevice。
 */
data class BindingDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val manufacturerData: ByteArray?,
    /**
     * 广播 service data 含 Kuyi Harmony UUID（0000fdee-...）。
     * 这类设备不能用 manufacturer data 解析身份 MAC，必须连接后读 2a23。
     */
    val harmony: Boolean = false,
)

/**
 * QGJ 兜底扫描结果。
 *
 * - [identityMatch]：广播身份与服务器 mac 直接匹配，可直接连接 [BindingDevice.address]。
 * - [harmonyCandidates]：名字匹配的 Harmony 设备；身份只能在连接后读 2a23 校验。
 */
data class QgjScanOutcome(
    val identityMatch: BindingDevice?,
    val harmonyCandidates: List<BindingDevice>,
)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    suspend fun findByName(name: String, timeoutMs: Long = 8_000): String? {
        val adapter = bluetoothAdapter()
        if (!adapter.isEnabled) throw BleException.BluetoothDisabled()
        if (!hasScanPermission()) throw BleException.PermissionDenied()

        adapter.bondedDevices
            ?.firstOrNull { it.name?.startsWith(name) == true }
            ?.let { return it.address }

        val scanner = adapter.bluetoothLeScanner ?: throw BleException.DeviceNotFound()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var finished = false
            lateinit var callback: ScanCallback

            fun finish(address: String?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) continuation.resume(address)
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val deviceName = result.scanRecord?.deviceName
                        ?: runCatching { result.device.name }.getOrNull()
                    if (deviceName?.startsWith(name) == true) {
                        finish(result.device.address)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(null)
                }
            }

            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
            }

            try {
                scanner.startScan(null, settings, callback)
                handler.postDelayed({ finish(null) }, timeoutMs)
            } catch (t: SecurityException) {
                finish(null)
            } catch (t: Throwable) {
                finish(null)
            }
        }
    }

    /**
     * QGJ 控车兜底扫描：按服务器 `mac`（车辆身份 MAC）匹配广播身份。
     *
     * 匹配规则与 Kuyi `ScannedDevice` 一致。Harmony 的 System ID 只能在连接后从
     * 180a/2a23 读取，因此这里按服务器名称或官方 QGJ 广播前缀收集候选，
     * 由调用方连接后校验；名称不一致时仍保留其他 FDEE 设备作为补充候选。
     */
    suspend fun scanQgj(
        identityMac: String,
        nameHint: String? = null,
        timeoutMs: Long = 8_000,
        maxHarmonyCandidates: Int = 4,
    ): QgjScanOutcome {
        val adapter = bluetoothAdapter()
        if (!adapter.isEnabled) throw BleException.BluetoothDisabled()
        if (!hasScanPermission()) throw BleException.PermissionDenied()

        // 已配对设备可能不再广播：Kuyi 从 Q_BASH_<身份 MAC> 形式的名称解析身份。
        val bondedMatch = runCatching {
            adapter.bondedDevices.orEmpty().firstOrNull { device ->
                val name = device.name ?: return@firstOrNull false
                name.startsWith("Q_BASH_") &&
                    QgjCodec.matchesIdentity(name.substring(7), identityMac)
            }
        }.getOrNull()
        if (bondedMatch != null) {
            return QgjScanOutcome(
                identityMatch = BindingDevice(
                    name = runCatching { bondedMatch.name }.getOrNull().orEmpty(),
                    address = bondedMatch.address,
                    rssi = Int.MAX_VALUE,
                    manufacturerData = null,
                ),
                harmonyCandidates = emptyList(),
            )
        }

        val scanner = adapter.bluetoothLeScanner ?: throw BleException.DeviceNotFound()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val qgjPrefixes = BindingNames.matches
            .filter { it.modelType == 8 || it.modelType == 283 }
            .map { it.prefix }

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val preferred = LinkedHashMap<String, BindingDevice>()
            val others = LinkedHashMap<String, BindingDevice>()
            var finished = false
            lateinit var callback: ScanCallback

            fun finish(match: BindingDevice?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) {
                    val ordered = preferred.values.sortedByDescending { it.rssi } +
                        others.values.sortedByDescending { it.rssi }
                    continuation.resume(
                        QgjScanOutcome(
                            identityMatch = match,
                            harmonyCandidates = ordered
                                .distinctBy { it.address }
                                .take(maxHarmonyCandidates),
                        ),
                    )
                }
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (finished) return
                    val record = result.scanRecord
                    val name = record?.deviceName
                        ?: runCatching { result.device.name }.getOrNull()
                        ?: ""
                    val data = record
                        ?.getManufacturerSpecificData()
                        ?.takeIf { it.size() > 0 }
                        ?.let { array -> array.get(array.keyAt(0)) }
                    val harmony = record?.serviceData?.keys
                        ?.let { keys -> QgjCodec.isHarmonyServiceData(keys.map { it.uuid }) }
                        ?: false
                    val identity = QgjCodec.identityMacForScan(
                        result.device.address,
                        data,
                        harmony,
                    )
                    if (QgjCodec.matchesIdentity(identity, identityMac)) {
                        finish(
                            BindingDevice(
                                name = name,
                                address = result.device.address,
                                rssi = result.rssi,
                                manufacturerData = data?.copyOf(),
                                harmony = harmony,
                            ),
                        )
                        return
                    }
                    if (!harmony) return
                    val preferredName = (!nameHint.isNullOrBlank() && name.startsWith(nameHint)) ||
                        qgjPrefixes.any { name.startsWith(it) }
                    val candidates = if (preferredName) preferred else others
                    val existing = candidates[result.device.address]
                    if (existing == null || result.rssi > existing.rssi) {
                        candidates[result.device.address] = BindingDevice(
                            name = name,
                            address = result.device.address,
                            rssi = result.rssi,
                            manufacturerData = data?.copyOf(),
                            harmony = true,
                        )
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(null)
                }
            }

            continuation.invokeOnCancellation {
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
            }

            try {
                scanner.startScan(null, settings, callback)
                handler.postDelayed({ finish(null) }, timeoutMs)
            } catch (t: SecurityException) {
                finish(null)
            } catch (t: Throwable) {
                finish(null)
            }
        }
    }

    /**
     * 绑定模式扫描：只返回官方绑定广播名（TAILG_* / QGJ）的设备。
     * 扫描持续到超时，返回去重后的列表；调用方让用户选择具体车辆。
     */
    suspend fun scanForBinding(
        timeoutMs: Long = 30_000,
        onDevice: ((BindingDevice) -> Unit)? = null,
    ): List<BindingDevice> {
        val adapter = bluetoothAdapter()
        if (!adapter.isEnabled) throw BleException.BluetoothDisabled()
        if (!hasScanPermission()) throw BleException.PermissionDenied()

        val scanner = adapter.bluetoothLeScanner ?: throw BleException.DeviceNotFound()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val found = LinkedHashMap<String, BindingDevice>()
            var finished = false
            lateinit var callback: ScanCallback

            fun finish() {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
                if (continuation.isActive) continuation.resume(found.values.toList())
            }

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (finished) return
                    val name = result.scanRecord?.deviceName
                        ?: runCatching { result.device.name }.getOrNull()
                        ?: return
                    if (!BindingNames.isBindingDevice(name)) return
                    val data = result.scanRecord
                        ?.getManufacturerSpecificData()
                        ?.takeIf { it.size() > 0 }
                        ?.let { array -> array.get(array.keyAt(0)) }
                    val harmony = result.scanRecord?.serviceData?.keys
                        ?.let { keys ->
                            QgjCodec.isHarmonyServiceData(keys.map { it.uuid })
                        }
                        ?: false
                    val existing = found[result.device.address]
                    if (existing == null || result.rssi > existing.rssi) {
                        val device = BindingDevice(
                            name = name,
                            address = result.device.address,
                            rssi = result.rssi,
                            manufacturerData = data?.copyOf(),
                            harmony = harmony,
                        )
                        found[result.device.address] = device
                        onDevice?.invoke(device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish()
                }
            }

            continuation.invokeOnCancellation {
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { scanner.stopScan(callback) }
            }

            try {
                scanner.startScan(null, settings, callback)
                handler.postDelayed({ finish() }, timeoutMs)
            } catch (t: SecurityException) {
                finish()
            } catch (t: Throwable) {
                finish()
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw BleException.BluetoothDisabled()
        return manager.adapter ?: throw BleException.BluetoothDisabled()
    }

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
