package com.ywpc.tlkey.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 原生 BluetoothGatt 的协程封装。
 * 回调只负责完成 Deferred 或向 Flow 发事件，业务流程由 BleSession 负责。
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _notifications = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications = _notifications.asSharedFlow()

    private val _disconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnected = _disconnected.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var discoverDeferred: CompletableDeferred<List<BluetoothGattService>>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var descriptorDeferred: CompletableDeferred<Unit>? = null
    private var writeDeferred: CompletableDeferred<Unit>? = null
    private var readDeferred: CompletableDeferred<ByteArray>? = null
    private var pendingWriteCharacteristic: BluetoothGattCharacteristic? = null
    private val writeMutex = Mutex()

    @Volatile
    private var closed = false

    suspend fun connect(timeoutMs: Long = 10_000) {
        if (closed) throw BleException.Disconnected()
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        val g = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: SecurityException) {
            throw BleException.PermissionDenied()
        } catch (t: Throwable) {
            throw BleException.Unknown(t)
        } ?: throw BleException.DeviceNotFound()

        gatt = g
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            close()
            throw BleException.ConnectTimeout()
        } catch (t: Throwable) {
            close()
            throw t
        }
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
    }

    suspend fun requestMtu(mtu: Int, timeoutMs: Long = 1_000): Int {
        val g = gatt ?: throw BleException.Disconnected()
        val deferred = CompletableDeferred<Int>()
        mtuDeferred = deferred
        val requested = try {
            g.requestMtu(mtu)
        } catch (t: SecurityException) {
            throw BleException.PermissionDenied()
        }
        if (!requested) return 0
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            0
        }
    }

    suspend fun discoverServices(timeoutMs: Long = 5_000): List<BluetoothGattService> {
        val g = gatt ?: throw BleException.Disconnected()
        val deferred = CompletableDeferred<List<BluetoothGattService>>()
        discoverDeferred = deferred
        if (!g.discoverServices()) throw BleException.GattError(-1)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            throw BleException.GattError(-1)
        }
    }

    suspend fun enableNotifications(
        characteristic: BluetoothGattCharacteristic,
        indication: Boolean,
        timeoutMs: Long = 3_000,
    ) {
        val g = gatt ?: throw BleException.Disconnected()
        if (!g.setCharacteristicNotification(characteristic, true)) {
            throw BleException.GattError(-1)
        }
        val descriptor = characteristic.getDescriptor(BleUuids.CCCD)
            ?: throw BleException.ServiceNotFound()
        val deferred = CompletableDeferred<Unit>()
        descriptorDeferred = deferred
        val value = if (indication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        } catch (t: SecurityException) {
            throw BleException.PermissionDenied()
        }
        if (!ok) throw BleException.GattError(-1)
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            throw BleException.NotificationTimeout()
        }
    }

    suspend fun write(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        timeoutMs: Long = 3_000,
    ) = writeMutex.withLock {
        val g = gatt ?: throw BleException.Disconnected()
        val deferred = CompletableDeferred<Unit>()
        writeDeferred = deferred
        pendingWriteCharacteristic = characteristic
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                // Android < 33 的默认 writeType 取决于特征属性；显式按属性选择，
                // 避免 feb5 只声明 WRITE 时被当成 no-response 导致回调异常。
                @Suppress("DEPRECATION")
                characteristic.writeType =
                    if (characteristic.properties and
                        BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    ) {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        } catch (t: SecurityException) {
            pendingWriteCharacteristic = null
            throw BleException.PermissionDenied()
        }
        if (!ok) {
            pendingWriteCharacteristic = null
            throw BleException.GattError(-1)
        }
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            throw BleException.CommandTimeout()
        } finally {
            pendingWriteCharacteristic = null
            writeDeferred = null
        }
    }

    /** 读取 2a23 / 2a40 / 2a41 等设备信息特征值。绑定流程使用。 */
    suspend fun read(
        characteristic: BluetoothGattCharacteristic,
        timeoutMs: Long = 3_000,
    ): ByteArray {
        val g = gatt ?: throw BleException.Disconnected()
        val deferred = CompletableDeferred<ByteArray>()
        readDeferred = deferred
        val ok = try {
            g.readCharacteristic(characteristic)
        } catch (t: SecurityException) {
            readDeferred = null
            throw BleException.PermissionDenied()
        }
        if (!ok) {
            readDeferred = null
            throw BleException.GattError(-1)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            throw BleException.ReadTimeout()
        } finally {
            readDeferred = null
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val error = BleException.Disconnected()
        connectDeferred?.completeExceptionally(error)
        discoverDeferred?.completeExceptionally(error)
        mtuDeferred?.completeExceptionally(error)
        descriptorDeferred?.completeExceptionally(error)
        writeDeferred?.completeExceptionally(error)
        readDeferred?.completeExceptionally(error)
        val g = gatt
        gatt = null
        runCatching { g?.disconnect() }
        runCatching { g?.close() }
        scope.cancel()
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                when {
                    status == BluetoothGatt.GATT_SUCCESS &&
                        newState == BluetoothProfile.STATE_CONNECTED -> {
                        connectDeferred?.complete(Unit)
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        failAll(
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                BleException.Disconnected()
                            } else {
                                BleException.GattError(status)
                            },
                        )
                        _disconnected.tryEmit(Unit)
                    }

                    status != BluetoothGatt.GATT_SUCCESS -> {
                        failAll(BleException.GattError(status))
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    discoverDeferred?.complete(g.services.orEmpty())
                } else {
                    discoverDeferred?.completeExceptionally(BleException.GattError(status))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtuDeferred?.complete(mtu)
                } else {
                    mtuDeferred?.complete(0)
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            scope.launch {
                if (descriptor.uuid != BleUuids.CCCD) return@launch
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    descriptorDeferred?.complete(Unit)
                } else {
                    descriptorDeferred?.completeExceptionally(BleException.GattError(status))
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch {
                if (characteristic.uuid != pendingWriteCharacteristic?.uuid) return@launch
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeDeferred?.complete(Unit)
                } else {
                    writeDeferred?.completeExceptionally(BleException.GattError(status))
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                completeRead(characteristic.value, status)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                completeRead(value, status)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                emitNotification(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                emitNotification(value)
            }
        }
    }

    private fun emitNotification(value: ByteArray?) {
        if (value != null) _notifications.tryEmit(value.copyOf())
    }

    private fun completeRead(value: ByteArray?, status: Int) {
        scope.launch {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDeferred?.complete(value?.copyOf() ?: ByteArray(0))
            } else {
                readDeferred?.completeExceptionally(BleException.GattError(status))
            }
        }
    }

    private fun failAll(error: Throwable) {
        connectDeferred?.completeExceptionally(error)
        discoverDeferred?.completeExceptionally(error)
        mtuDeferred?.completeExceptionally(error)
        descriptorDeferred?.completeExceptionally(error)
        writeDeferred?.completeExceptionally(error)
        readDeferred?.completeExceptionally(error)
    }
}
