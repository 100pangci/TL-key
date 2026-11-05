package com.ywpc.tlkey.cloud

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 最小 MQTT 3.1.1 客户端，只覆盖官方 App 用到的能力：
 * clean session、用户名/密码、QoS 0 订阅与发布、keepAlive、PINGREQ。
 *
 * 不引入第三方 MQTT 库；TLS 使用系统默认信任库与主机名校验
 * （官方在 ssl:// 上信任所有证书，本项目不复制该行为）。
 */
class MiniMqttClient(
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
    private val onConnectionLost: (Throwable?) -> Unit,
) {

    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var connected = false

    private var readerThread: Thread? = null
    private var pingThread: Thread? = null
    private var subAckLatch: CountDownLatch? = null

    private class Packet(val type: Int, val flags: Int, val body: ByteArray)

    fun connect(endpoint: MqttEndpoint) {
        disconnect()
        try {
            val uri = URI(endpoint.host)
            val host = uri.host ?: throw IOException("MQTT 地址缺少主机名")
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme == "ssl" -> 8883
                else -> 1883
            }

            val tcp = Socket()
            tcp.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tcp.tcpNoDelay = true
            val active = if (uri.scheme == "ssl") {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(tcp, host, port, true) as SSLSocket).apply {
                    // 握手也在超时内完成，避免 TLS 阶段无限阻塞控制协程。
                    soTimeout = CONNECT_TIMEOUT_MS
                    sslParameters = sslParameters.apply {
                        // 与 HttpsURLConnection 相同的证书链 + 主机名校验。
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    startHandshake()
                }
            } else {
                tcp
            }

            active.soTimeout = CONNECT_TIMEOUT_MS
            val out = BufferedOutputStream(active.getOutputStream())
            val inp = BufferedInputStream(active.getInputStream())
            socket = active
            output = out

            writePacket(out, 0x10, buildConnectBody(endpoint))
            val connAck = readPacket(inp)
            if (connAck.type != TYPE_CONNACK || connAck.body.size < 2 || connAck.body[1].toInt() != 0) {
                throw IOException("MQTT 连接被拒绝")
            }

            active.soTimeout = 0
            val latch = CountDownLatch(1)
            subAckLatch = latch
            running = true
            startReader(active, inp)
            writePacket(out, 0x82, buildSubscribeBody(endpoint.subscribeTopics))
            if (!latch.await(SUBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("MQTT 订阅超时")
            }
            subAckLatch = null
            connected = true
            startPing()
        } catch (t: Throwable) {
            closeSocket()
            running = false
            connected = false
            throw t
        }
    }

    fun publish(topic: String, payload: ByteArray) {
        if (!connected) throw IOException("MQTT 未连接")
        val out = output ?: throw IOException("MQTT 未连接")
        val body = buildPublishBody(topic, payload)
        writePacket(out, 0x30, body)
    }

    fun disconnect() {
        val wasRunning = running
        running = false
        connected = false
        if (wasRunning) {
            val out = output
            if (out != null) runCatching { writePacket(out, 0xE0, ByteArray(0)) }
        }
        closeSocket()
        readerThread?.interrupt()
        pingThread?.interrupt()
        readerThread = null
        pingThread = null
        subAckLatch = null
    }

    private fun startReader(active: Socket, input: InputStream) {
        readerThread = Thread({
            try {
                while (running) {
                    val packet = readPacket(input)
                    when (packet.type) {
                        TYPE_PUBLISH -> dispatchPublish(packet)
                        TYPE_SUBACK -> subAckLatch?.countDown()
                        TYPE_PINGRESP -> Unit
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                if (running) handleLost(t)
            }
        }, "tlkey-mqtt-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPing() {
        pingThread = Thread({
            try {
                while (running) {
                    Thread.sleep(PING_INTERVAL_MS)
                    if (!running) return@Thread
                    val out = output ?: return@Thread
                    writePacket(out, 0xC0, ByteArray(0))
                }
            } catch (_: InterruptedException) {
                // 主动断开。
            } catch (t: Throwable) {
                handleLost(t)
            }
        }, "tlkey-mqtt-ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleLost(cause: Throwable?) {
        if (!running) return
        running = false
        connected = false
        subAckLatch?.countDown()
        closeSocket()
        onConnectionLost(cause)
    }

    private fun dispatchPublish(packet: Packet) {
        val body = packet.body
        if (body.size < 2) return
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        var offset = 2 + topicLength
        if (offset > body.size) return
        val topic = String(body, 2, topicLength, Charsets.UTF_8)
        val qos = (packet.flags shr 1) and 0x03
        if (qos > 0) offset += 2 // QoS 1/2 的 packet identifier；订阅 QoS 0，正常不会出现。
        if (offset > body.size) return
        onMessage(topic, body.copyOfRange(offset, body.size))
    }

    private fun closeSocket() {
        val active = socket
        socket = null
        output = null
        runCatching { active?.close() }
    }

    private fun writePacket(out: OutputStream, header: Int, body: ByteArray) {
        synchronized(writeLock) {
            out.write(header)
            writeRemainingLength(out, body.size)
            out.write(body)
            out.flush()
        }
    }

    private fun readPacket(input: InputStream): Packet {
        val first = input.read()
        if (first < 0) throw EOFException("MQTT 连接已关闭")
        val remaining = readRemainingLength(input)
        val body = ByteArray(remaining)
        var offset = 0
        while (offset < remaining) {
            val read = input.read(body, offset, remaining - offset)
            if (read < 0) throw EOFException("MQTT 报文不完整")
            offset += read
        }
        return Packet(first and 0xF0, first and 0x0F, body)
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        for (index in 0 until 4) {
            val encoded = input.read()
            if (encoded < 0) throw EOFException("MQTT 连接已关闭")
            value += (encoded and 0x7F) * multiplier
            if (encoded and 0x80 == 0) return value
            multiplier *= 128
        }
        throw IOException("MQTT remaining length 非法")
    }

    private fun writeRemainingLength(out: OutputStream, length: Int) {
        var value = length
        do {
            var encoded = value % 128
            value /= 128
            if (value > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (value > 0)
    }

    private fun buildConnectBody(endpoint: MqttEndpoint): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        out.write(0x04)
        out.write('M'.code)
        out.write('Q'.code)
        out.write('T'.code)
        out.write('T'.code)
        out.write(0x04) // MQTT 3.1.1
        // username + password + cleanSession
        out.write(0xC2)
        out.write(KEEP_ALIVE_SECONDS shr 8)
        out.write(KEEP_ALIVE_SECONDS and 0xFF)
        out.write(utf8(endpoint.clientId))
        out.write(utf8(endpoint.username))
        out.write(utf8(endpoint.password))
        return out.toByteArray()
    }

    private fun buildSubscribeBody(topics: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)
        out.write(0x01) // packet identifier
        topics.forEach { topic ->
            out.write(utf8(topic))
            out.write(0x00) // QoS 0
        }
        return out.toByteArray()
    }

    private fun buildPublishBody(topic: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(utf8(topic))
        out.write(payload)
        return out.toByteArray()
    }

    private fun utf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "MQTT 字符串过长" }
        return byteArrayOf(
            ((bytes.size shr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte(),
        ) + bytes
    }

    companion object {
        private const val TYPE_CONNACK = 0x20
        private const val TYPE_PUBLISH = 0x30
        private const val TYPE_SUBACK = 0x90
        private const val TYPE_PINGRESP = 0xD0

        private const val KEEP_ALIVE_SECONDS = 60
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SUBACK_TIMEOUT_MS = 5_000L
        private const val PING_INTERVAL_MS = 30_000L
    }
}
