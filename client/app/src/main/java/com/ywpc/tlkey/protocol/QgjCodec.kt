package com.ywpc.tlkey.protocol

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * QGJ（Kuyi SDK）帧编解码。来源：
 * - `com.kuyi.g.c`：V1 ECU，A5 00 + 总长(2B) + cmd(2B) + payload
 * - `com.kuyi.h.d`：V2 ECU，A7 00 + (payload+2)(2B) + cmd(2B) + payload
 * - `com.kuyi.g.e`：GPS/仪表，A6 00 + (payload+2)(2B) + cmd(2B) + payload
 *
 * 长度字段均为 16 位大端；命令 ID 也是 16 位大端。
 */
enum class QgjVariant {
    V1,
    V2,
    GPS,
}

data class QgjFrame(
    val variant: QgjVariant,
    val commandId: Int,
    val payload: ByteArray,
    /** 非空表示车端返回了错误码（对应 Kuyi 的 h.c/h.e 等）。 */
    val errorCode: Int? = null,
)

object QgjCodec {

    const val CMD_V1_LOGIN = 4098
    const val CMD_V1_SET_STATUS = 8193
    const val CMD_V1_BIND = 20481

    const val CMD_V2_LOGIN = 4097
    const val CMD_V2_SET_STATUS = 4098
    const val CMD_V2_BIND = 4096

    const val CMD_GPS_QUERY_IMEI = 12377
    const val CMD_GPS_CUSTOM_DATA_GET = 13825

    /** 283 绑定完成后：ECU 连接本地 GPS 模块（`connectGpsByUart`，无 payload）。 */
    const val CMD_ECU_GPS_CONNECT_LOCAL = 8482
    /** `gpsActivateSet(ON, 101)`：payload 固定 6 字节，见 [gpsActivatePayload]。 */
    const val CMD_GPS_SET_STATUS = 12373

    /** Kuyi `ScannedDevice.isHarmony()` 判定用：广播 service data 含此 UUID。 */
    val HARMONY_SERVICE_DATA_UUID: UUID =
        UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb")

    private const val HEADER_V1 = 0xA5
    private const val HEADER_V2 = 0xA7
    private const val HEADER_GPS = 0xA6
    private const val MAX_V1_PASSWORDS = 6
    private const val MAX_V2_PASSWORDS = 2

    /** NomalCommand 枚举的 opCode，来自 `classes13.dex` 的 <clinit>。 */
    fun opCode(action: ControlAction): Int = when (action) {
        ControlAction.UNLOCK -> 1        // DeviceOutSafe
        ControlAction.LOCK -> 2          // DeviceSetSafe
        ControlAction.POWER_ON -> 3      // DeviceOpenEleDoor
        ControlAction.POWER_OFF -> 4     // DeviceCloseEleDoor
        ControlAction.SEAT -> 7          // DeviceOpenSeat
        ControlAction.FIND -> 8          // DeviceFindBike
    }

    fun frame(variant: QgjVariant, commandId: Int, payload: ByteArray): ByteArray {
        val header = when (variant) {
            QgjVariant.V1 -> HEADER_V1
            QgjVariant.V2 -> HEADER_V2
            QgjVariant.GPS -> HEADER_GPS
        }
        // V1 与 GPS 的长度字段是整帧长度；V2 是 payload + 2。
        val lengthField = if (variant == QgjVariant.V2) {
            payload.size + 2
        } else {
            payload.size + 6
        }
        return ByteArray(6 + payload.size).also { out ->
            out[0] = header.toByte()
            out[1] = 0
            out[2] = (lengthField ushr 8).toByte()
            out[3] = lengthField.toByte()
            out[4] = (commandId ushr 8).toByte()
            out[5] = commandId.toByte()
            payload.copyInto(out, 6)
        }
    }

    fun parse(data: ByteArray): QgjFrame? {
        if (data.size < 4) return null
        val header = data[0].toInt() and 0xFF
        val variant = when (header) {
            HEADER_V1 -> QgjVariant.V1
            HEADER_V2 -> QgjVariant.V2
            HEADER_GPS -> QgjVariant.GPS
            else -> return null
        }

        // 错误帧不保证长度字段完整：V2 看第二字节高半字节，GPS 看低半字节（仅 1..5）。
        if (variant == QgjVariant.V2) {
            val statusNibble = (data[1].toInt() shr 4) and 0x0F
            if (statusNibble != 0) {
                val error = when (statusNibble) {
                    1 -> -1
                    2 -> -2
                    3 -> -3
                    4 -> -4
                    5 -> -5
                    6 -> -6
                    else -> -255
                }
                return QgjFrame(variant, -1, ByteArray(0), error)
            }
        } else if (variant == QgjVariant.GPS) {
            val statusNibble = data[1].toInt() and 0x0F
            if (statusNibble in 1..5) {
                return QgjFrame(
                    variant,
                    -1,
                    ByteArray(0),
                    if (statusNibble == 3) -3 else -1,
                )
            }
        }
        if (data.size < 6) return null

        val lengthField = readU16(data, 2)
        val validLength = if (variant == QgjVariant.V2) {
            lengthField == data.size - 4
        } else {
            lengthField == data.size
        }
        if (!validLength) return null

        val commandId = readU16(data, 4)
        if (variant == QgjVariant.V1 && (commandId ushr 12) == 0x0F) {
            val error = when (commandId) {
                0xF001 -> -3
                0xF002, 0xF003, 0xF004 -> -1
                else -> -1
            }
            return QgjFrame(variant, commandId, ByteArray(0), error)
        }
        return QgjFrame(variant, commandId, data.copyOfRange(6, data.size))
    }

    fun passwordValue(decimal: String): Int {
        val value = decimal.trim().toLongOrNull()
            ?: throw ProtocolException("QGJ 密码不是十进制整数")
        return value.toInt()
    }

    fun uidValue(uid: String): Int {
        val value = uid.trim().toLongOrNull()
            ?: throw ProtocolException("QGJ uid 不是十进制整数")
        return value.toInt()
    }

    fun loginPayload(password: String, uid: String, variant: QgjVariant): ByteArray {
        val passwordValue = passwordValue(password)
        return if (variant == QgjVariant.V1) {
            int32(passwordValue)
        } else {
            int32(passwordValue) + int32(uidValue(uid))
        }
    }

    fun controlPayload(action: ControlAction): ByteArray =
        byteArrayOf(opCode(action).toByte())

    /**
     * V1：main + children 依次 4 字节写入，最多 6 个。
     * V2：main + child 固定 8 字节；非 legacy 设备额外带 BLT 名称前缀，官方 license 为 "TL"。
     */
    fun bindPayload(
        mainPassword: String,
        childPasswords: List<String>,
        variant: QgjVariant,
        legacyV2: Boolean,
        namePrefix: String = "TL",
    ): ByteArray {
        val all = (listOf(mainPassword) + childPasswords)
            .filter { it.isNotBlank() }
            .map(::passwordValue)
        if (all.isEmpty()) throw ProtocolException("缺少 QGJ 蓝牙密码")

        return when (variant) {
            QgjVariant.V1 -> all.take(MAX_V1_PASSWORDS)
                .flatMap { int32(it).toList() }
                .toByteArray()

            QgjVariant.V2 -> {
                val values = all.take(MAX_V2_PASSWORDS)
                val base = values.flatMap { int32(it).toList() }.toByteArray()
                if (legacyV2) {
                    base
                } else {
                    val prefix = namePrefix.toByteArray(StandardCharsets.US_ASCII)
                    base + byteArrayOf(prefix.size.toByte()) + prefix
                }
            }

            QgjVariant.GPS -> throw ProtocolException("GPS 通道不能用于绑定")
        }
    }

    /** `CommonDataCodec.dataEncode(String)`：原样 UTF-8 字节，无长度前缀。 */
    fun gpsStringPayload(value: String): ByteArray =
        value.toByteArray(StandardCharsets.UTF_8)

    /**
     * `gpsActivateSet(SwitchState.ON, 101)` 经 `com.kuyi.g.f0.encode` 得到的 payload：
     * - `EncoderData.newValue(UINT8).pushValue(modeCode, UINT8)`，Activate → 0x02
     * - `param` = `[SwitchState.ON.ordinal=1, 101 as UINT32 BE = 00 00 00 65]`
     */
    fun gpsActivatePayload(): ByteArray =
        byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x65)

    fun isHarmonyServiceData(uuids: Iterable<UUID>): Boolean =
        uuids.any { it == HARMONY_SERVICE_DATA_UUID }

    /** `CommonDataCodec.dataDecode(String)`：直接取剩余字节。 */
    fun decodeGpsString(payload: ByteArray): String =
        String(payload, StandardCharsets.UTF_8)

    fun statusOk(payload: ByteArray): Boolean =
        payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 1

    /**
     * 广播 ManufacturerData 的解析与 Kuyi `ScannedDevice` 一致：
     * - 8 字节：byte0 bit7 = IS_LEGACY_V2，byte2..7 = identity MAC
     * - 6 字节：legacy=true，byte0..5 = identity MAC
     * - 无广播数据时 SDK 不写 IS_LEGACY_V2，读取结果为 false
     */
    fun isLegacyV2(manufacturerData: ByteArray?): Boolean {
        if (manufacturerData == null || manufacturerData.isEmpty()) return false
        if (manufacturerData.size != 8) return true
        return (manufacturerData[0].toInt() and 0x80) != 0
    }

    fun identityMac(manufacturerData: ByteArray?): String? {
        val bytes = when {
            manufacturerData == null -> return null
            manufacturerData.size == 8 -> manufacturerData.copyOfRange(2, 8)
            manufacturerData.size == 6 -> manufacturerData.copyOfRange(0, 6)
            else -> return null
        }
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
