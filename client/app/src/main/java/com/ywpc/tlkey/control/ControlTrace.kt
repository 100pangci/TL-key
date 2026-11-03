package com.ywpc.tlkey.control

import com.ywpc.tlkey.ble.TraceSink
import com.ywpc.tlkey.protocol.ControlAction

/**
 * 一次控制的耗时轨迹，全部使用相对起点的毫秒数。
 * 不包含 token、密码、车辆识别码等敏感数据。
 */
class ControlTrace(val action: ControlAction) : TraceSink {

    private val startedNanos = System.nanoTime()
    private val marks = LinkedHashMap<String, Long>()
    private val lock = Any()

    override fun mark(name: String) {
        val elapsed = elapsedMs()
        synchronized(lock) { marks[name] = elapsed }
    }

    val totalMs: Long
        get() = elapsedMs()

    fun summary(): String {
        val body = synchronized(lock) {
            marks.entries.joinToString(" · ") { (name, at) ->
                "${label(name)} ${at}ms"
            }
        }
        return if (body.isBlank()) {
            "总计 ${totalMs}ms"
        } else {
            "$body · 总计 ${totalMs}ms"
        }
    }

    private fun elapsedMs(): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private fun label(name: String): String = when (name) {
        "device" -> "设备"
        "connect" -> "连接"
        "mtu" -> "MTU"
        "service" -> "服务"
        "notify" -> "通知"
        "token" -> "Token"
        "auth" -> "鉴权"
        "command" -> "发送"
        "ack" -> "应答"
        "session" -> "复用连接"
        else -> name
    }
}
