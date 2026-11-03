package com.ywpc.tlkey.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MqttConnectionState {
    data object Idle : MqttConnectionState
    data object Connecting : MqttConnectionState
    data class Connected(val endpoint: MqttEndpoint) : MqttConnectionState
    data class Failed(val message: String) : MqttConnectionState
    data class Disconnected(val reason: String?) : MqttConnectionState
}

data class MqttSendResult(
    val ok: Boolean,
    val message: String,
    val status: MqttStatus? = null,
)

/**
 * 云控客户端。
 *
 * 与官方 3.6.2 一致：MQTT 3.1.1、QoS 0、cleanSession、keepAlive 60、关闭自动重连。
 * 安全差异：官方对 ssl:// 使用 trust-all；这里使用系统默认信任库与主机名校验，
 * 证书异常时直接失败，而不是忽略。
 */
class MqttCloudClient {

    private val _state = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Idle)
    val state: StateFlow<MqttConnectionState> = _state.asStateFlow()

    private val _status = MutableStateFlow(MqttStatus())
    val status: StateFlow<MqttStatus> = _status.asStateFlow()

    private val _lastMessageAt = MutableStateFlow(0L)
    val lastMessageAt: StateFlow<Long> = _lastMessageAt.asStateFlow()

    private val lock = Any()
    private var client: MiniMqttClient? = null
    private var endpoint: MqttEndpoint? = null
    private var pending: PendingAck? = null

    private data class PendingAck(
        val command: CloudCommand,
        val deferred: CompletableDeferred<MqttSendResult>,
    )

    /** 阻塞调用；请在 IO 线程执行。 */
    fun connect(target: MqttEndpoint) {
        closeClient()

        _state.value = MqttConnectionState.Connecting
        _status.value = MqttStatus()
        _lastMessageAt.value = 0L

        val created = MiniMqttClient(
            onMessage = { _, payload ->
                handleMessage(String(payload, Charsets.UTF_8))
            },
            onConnectionLost = { cause ->
                val pendingAck = synchronized(lock) {
                    pending.also { pending = null }
                }
                pendingAck?.deferred?.complete(MqttSendResult(false, "连接已断开"))
                synchronized(lock) {
                    client = null
                    endpoint = null
                }
                _state.value = MqttConnectionState.Disconnected(cause?.message)
            },
        )

        try {
            created.connect(target)
            synchronized(lock) {
                client = created
                endpoint = target
            }
            _state.value = MqttConnectionState.Connected(target)
        } catch (t: Throwable) {
            runCatching { created.disconnect() }
            synchronized(lock) {
                client = null
                endpoint = null
            }
            _state.value = MqttConnectionState.Failed(
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    fun disconnect(reason: String? = null) {
        closeClient()
        _state.value = if (reason == null) {
            MqttConnectionState.Idle
        } else {
            MqttConnectionState.Disconnected(reason)
        }
    }

    suspend fun send(command: CloudCommand): MqttSendResult {
        val active = synchronized(lock) {
            val ep = endpoint
            val mqtt = client
            if (ep != null && mqtt != null) ep to mqtt else null
        } ?: return MqttSendResult(false, "云端未连接")
        val target = active.first
        val mqtt = active.second
        val payload = MqttPayload
            .command(target.imei, command.wire)
            .toByteArray(Charsets.UTF_8)

        if (!command.needsAck) {
            return withContext(Dispatchers.IO) {
                try {
                    mqtt.publish(target.publishTopic, payload)
                    MqttSendResult(true, "${command.label}指令已发送")
                } catch (t: Throwable) {
                    MqttSendResult(false, "发送失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }

        val deferred = CompletableDeferred<MqttSendResult>()
        val ack = PendingAck(command, deferred)
        synchronized(lock) {
            pending?.deferred?.complete(MqttSendResult(false, "上一个指令仍未完成"))
            pending = ack
        }

        val published = withContext(Dispatchers.IO) {
            try {
                mqtt.publish(target.publishTopic, payload)
                true
            } catch (t: Throwable) {
                false
            }
        }
        if (!published) {
            clearPending(ack)
            return MqttSendResult(false, "发送失败")
        }

        return withTimeoutOrNull(ACK_TIMEOUT_MS) { deferred.await() } ?: run {
            clearPending(ack)
            MqttSendResult(false, "${command.label}超时，未收到车辆回执")
        }
    }

    private fun handleMessage(raw: String) {
        val fresh = MqttPayload.parse(raw) ?: return
        _status.value = _status.value.mergedWith(fresh)
        _lastMessageAt.value = System.currentTimeMillis()

        val current = synchronized(lock) { pending } ?: return
        val error = current.command.errorCodeOf(fresh)
        val success = current.command.successOf(fresh)
        if (error == null && success != true) return

        synchronized(lock) {
            if (pending === current) pending = null
        }
        val result = if (error != null) {
            MqttSendResult(false, "车辆返回错误码 $error", fresh)
        } else {
            MqttSendResult(true, "${current.command.label}成功", fresh)
        }
        current.deferred.complete(result)
    }

    private fun closeClient() {
        val mqtt: MiniMqttClient?
        val ack: PendingAck?
        synchronized(lock) {
            mqtt = client
            ack = pending
            client = null
            endpoint = null
            pending = null
        }
        ack?.deferred?.complete(MqttSendResult(false, "连接已断开"))
        mqtt?.disconnect()
    }

    private fun clearPending(ack: PendingAck) {
        synchronized(lock) {
            if (pending === ack) pending = null
        }
    }

    companion object {
        private const val ACK_TIMEOUT_MS = 15_000L
    }
}
