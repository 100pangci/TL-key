package com.ywpc.tlkey.protocol

/**
 * 85 帧 / QGJ 的 ECU 驾驶模式编解码。
 *
 * 官方来源：`TLinkBleManager.writeEcu / readEcu`、`TLinkBleManagerQgj`、
 * `EcuAbilityBean`、`EcuStatusBean`、`ExtraCharacteristicHandler`。
 *
 * ECU 特征：service `0000fcc0-...`，读特征 `fcc1` / `fbb1`（二选一），
 * 写入与读取都是**原始字节**，不走 85 帧的 AES 加密。
 *
 * 状态用 3 个 8 位字符串表示，字符串下标 0 是字节的最高位（MSB），
 * 与官方 `AESUtils.hexString2binaryString` 的位序一致。
 */
enum class EcuChannel { FCC1, FBB1 }

/** BLE 读回的能力位；null 表示该通道不上报这个能力。 */
data class EcuAbilities(
    val tcs: Boolean? = null,
    val sfr: Boolean? = null,
    val pcp: Boolean? = null,
    val hdc: Boolean? = null,
    val cc: Boolean? = null,
    val reverse: Boolean? = null,
    val sfs: Boolean? = null,
    val er: Boolean? = null,
    val lbcm: Boolean? = null,
    val podg: Boolean? = null,
    val hillParking: Boolean? = null,
    val slipSlope: Boolean? = null,
    val nitro: Boolean? = null,
    val temp: Boolean? = null,
    val motorTemp: Boolean? = null,
    val podgFuncType: Int? = null,
) {
    fun supports(type: Int): Boolean? = when (type) {
        EcuCodec.TYPE_TCS -> tcs
        EcuCodec.TYPE_SFR -> sfr
        EcuCodec.TYPE_PCP -> pcp
        EcuCodec.TYPE_HDC -> hdc
        EcuCodec.TYPE_CC -> cc
        EcuCodec.TYPE_REVERSE -> reverse
        EcuCodec.TYPE_SFS -> sfs
        EcuCodec.TYPE_ER -> er
        EcuCodec.TYPE_LBCM -> lbcm
        EcuCodec.TYPE_PODG -> podg
        EcuCodec.TYPE_HILL_PARKING -> hillParking
        EcuCodec.TYPE_SLIP_SLOPE -> slipSlope
        EcuCodec.TYPE_NITRO -> nitro
        EcuCodec.TYPE_TEMP -> temp
        EcuCodec.TYPE_MOTOR_TEMP -> motorTemp
        else -> null
    }
}

data class EcuState(
    val channel: EcuChannel,
    val state1: String,
    val state2: String,
    val state3: String,
    /** fbb1 专用；写回时必须原样带回。 */
    val value1: String? = null,
    val value2: String? = null,
    val abilities: EcuAbilities = EcuAbilities(),
    val raw: String = "",
) {
    fun flag(type: Int): Boolean? {
        val index = EcuCodec.flagIndex(type) ?: return null
        val state = when (EcuCodec.flagState(type)) {
            1 -> state1
            2 -> state2
            3 -> state3
            else -> return null
        }
        return state.getOrNull(index) == '1'
    }

    fun bits(type: Int): String? = when (type) {
        EcuCodec.TYPE_HDC -> state3.take(2)
        EcuCodec.TYPE_ER -> state2.substring(3, 5)
        EcuCodec.TYPE_PODG -> state2.substring(5, 8)
        else -> null
    }
}

object EcuCodec {

    const val TYPE_TCS = 1
    const val TYPE_SFR = 2
    const val TYPE_PCP = 3
    const val TYPE_HDC = 4
    const val TYPE_CC = 5
    const val TYPE_REVERSE = 6
    const val TYPE_SFS = 7
    const val TYPE_ER = 8
    const val TYPE_LBCM = 9
    const val TYPE_PODG = 10
    const val TYPE_HILL_PARKING = 11
    const val TYPE_SLIP_SLOPE = 12
    const val TYPE_NITRO = 13
    const val TYPE_TEMP = 14
    const val TYPE_MOTOR_TEMP = 15

    private const val FCC1_PREFIX = "00070002"
    private const val FBB1_PREFIX = "D0018E0A00FFFFFF00"

    fun supports(channel: EcuChannel, type: Int): Boolean = when (type) {
        in 1..13 -> true
        TYPE_TEMP, TYPE_MOTOR_TEMP -> channel == EcuChannel.FBB1
        else -> false
    }

    fun flagState(type: Int): Int = when (type) {
        TYPE_TCS, TYPE_SFR, TYPE_PCP, TYPE_CC, TYPE_REVERSE, TYPE_SFS -> 1
        TYPE_LBCM, TYPE_NITRO -> 2
        TYPE_HILL_PARKING, TYPE_SLIP_SLOPE, TYPE_TEMP, TYPE_MOTOR_TEMP -> 3
        else -> throw IllegalArgumentException("type $type 不是单 bit 设置")
    }

    fun flagIndex(type: Int): Int? = when (type) {
        TYPE_TCS -> 0
        TYPE_SFR -> 3
        TYPE_PCP -> 4
        TYPE_CC -> 5
        TYPE_REVERSE -> 6
        TYPE_SFS -> 2
        TYPE_LBCM -> 0
        TYPE_NITRO -> 1
        TYPE_HILL_PARKING -> 3
        TYPE_SLIP_SLOPE -> 2
        TYPE_TEMP -> 6
        TYPE_MOTOR_TEMP -> 7
        else -> null
    }

    /** fcc1 读回解析；短于官方要求的 24 个 hex 字符视为失败。 */
    fun parseFcc1(bytes: ByteArray): EcuState? = parseFcc1(hex(bytes))

    fun parseFcc1(rawHex: String): EcuState? {
        val hex = normalize(rawHex)
        if (hex.length < 24) return null
        val b4 = bitString(hex, 4) ?: return null
        val b5 = bitString(hex, 5) ?: return null
        val b6 = bitString(hex, 6) ?: return null
        val b8 = bitString(hex, 8) ?: return null
        val b9 = bitString(hex, 9) ?: return null
        val b10 = bitString(hex, 10) ?: return null
        val b11 = bitString(hex, 11) ?: return null

        val abilities = EcuAbilities(
            tcs = b4.bit(0),
            sfs = b4.bit(2),
            sfr = b4.bit(3),
            pcp = b4.bit(4),
            cc = b4.bit(5),
            reverse = b4.bit(6),
            er = b5.bit(5),
            lbcm = b5.bit(2),
            hdc = b6.bit(0),
            slipSlope = b6.bit(2),
            hillParking = b6.bit(3),
            podg = !(b11.bit(4) && !b11.bit(6)),
            nitro = false,
            podgFuncType = podgFuncType(b5),
        )
        return EcuState(
            channel = EcuChannel.FCC1,
            state1 = b8,
            state2 = b9,
            state3 = b10,
            abilities = abilities,
            raw = hex,
        )
    }

    /** fbb1 读回解析；b3 是后续负载长度。 */
    fun parseFbb1(bytes: ByteArray): EcuState? = parseFbb1(hex(bytes))

    fun parseFbb1(rawHex: String): EcuState? {
        val hex = normalize(rawHex)
        if (hex.length < 30) return null
        val lengthByte = hex.substring(6, 8).toIntOrNull(16) ?: return null
        val payloadStart = (lengthByte + 6) * 2
        if (payloadStart + 10 > hex.length) return null

        val b4 = bitString(hex, 4) ?: return null
        val b5 = bitString(hex, 5) ?: return null
        val b6 = bitString(hex, 6) ?: return null
        val value1 = hex.substring(payloadStart, payloadStart + 2)
        val state1 = bitString(hex, lengthByte + 7) ?: return null
        val state2 = bitString(hex, lengthByte + 8) ?: return null
        val state3 = bitString(hex, lengthByte + 9) ?: return null
        val value2 = hex.substring(payloadStart + 8, payloadStart + 10)
        val value2Bits = runCatching { bits(value2) }.getOrNull()

        val abilities = EcuAbilities(
            tcs = b4.bit(0),
            sfs = b4.bit(2),
            sfr = b4.bit(3),
            pcp = b4.bit(4),
            cc = b4.bit(5),
            reverse = b4.bit(6),
            er = b5.bit(5),
            nitro = b5.bit(1),
            hdc = b6.bit(0),
            slipSlope = b6.bit(2),
            hillParking = b6.bit(3),
            temp = b6.bit(6),
            motorTemp = b6.bit(7),
            // 官方 fbb1 分支固定 setEcuLBCMFunc(0)。
            lbcm = false,
            podg = value2Bits?.let { !(it.bit(4) && !it.bit(6)) },
            podgFuncType = podgFuncType(b5),
        )
        return EcuState(
            channel = EcuChannel.FBB1,
            state1 = state1,
            state2 = state2,
            state3 = state3,
            value1 = value1,
            value2 = value2,
            abilities = abilities,
            raw = hex,
        )
    }

    /** 单 bit 设置：按官方 writeEcu 的位位置改写。 */
    fun setFlag(state: EcuState, type: Int, enabled: Boolean): EcuState {
        require(supports(state.channel, type)) { "通道 ${state.channel} 不支持 type $type" }
        val index = flagIndex(type)
            ?: throw IllegalArgumentException("type $type 需要多位值")
        val bit = if (enabled) '1' else '0'
        fun replace(source: String) = source.substring(0, index) + bit +
            source.substring(index + 1)

        return when (flagState(type)) {
            1 -> state.copy(state1 = replace(state.state1))
            2 -> state.copy(state2 = replace(state.state2))
            3 -> state.copy(state3 = replace(state.state3))
            else -> error("unreachable")
        }
    }

    /** HDC / 能量回收 / 上电默认档位。value 只允许 0/1 组成的定长字符串。 */
    fun setBits(state: EcuState, type: Int, value: String): EcuState {
        require(supports(state.channel, type)) { "通道 ${state.channel} 不支持 type $type" }
        val (start, length) = when (type) {
            TYPE_HDC -> 0 to 2
            TYPE_ER -> 3 to 2
            TYPE_PODG -> 5 to 3
            else -> throw IllegalArgumentException("type $type 不是多位设置")
        }
        require(value.length == length && value.all { it == '0' || it == '1' }) {
            "type $type 需要 $length 位 0/1"
        }
        return when (type) {
            TYPE_HDC -> state.copy(
                state3 = state.state3.substring(0, start) + value +
                    state.state3.substring(start + length),
            )

            TYPE_ER -> state.copy(
                state2 = state.state2.substring(0, start) + value +
                    state.state2.substring(start + length),
            )

            TYPE_PODG -> state.copy(
                state2 = state.state2.substring(0, start) + value +
                    state.state2.substring(start + length),
            )

            else -> error("unreachable")
        }
    }

    /** 生成写入帧；fbb1 必须保留读回时拿到的 value1/value2。 */
    fun frame(state: EcuState): ByteArray {
        val state1 = byteHex(state.state1)
        val state2 = byteHex(state.state2)
        val state3 = byteHex(state.state3)
        val frame = when (state.channel) {
            EcuChannel.FCC1 -> FCC1_PREFIX + state1 + state2 + state3
            EcuChannel.FBB1 -> {
                val value1 = state.value1?.takeIf { it.length == 2 }
                    ?: throw IllegalStateException("fbb1 缺少 value1，无法写入")
                val value2 = state.value2?.takeIf { it.length == 2 }
                    ?: throw IllegalStateException("fbb1 缺少 value2，无法写入")
                FBB1_PREFIX + value1 + state1 + state2 + state3 + value2
            }
        }
        return frame.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun normalize(raw: String): String =
        raw.trim().filterNot { it.isWhitespace() }.uppercase()

    private fun bitString(hex: String, byteIndex: Int): String? {
        val start = byteIndex * 2
        if (start + 2 > hex.length) return null
        return runCatching { bits(hex.substring(start, start + 2)) }.getOrNull()
    }

    private fun bits(hexByte: String): String {
        require(hexByte.length == 2) { "需要 2 个 hex 字符" }
        val value = hexByte.toInt(16)
        return value.toString(2).padStart(8, '0')
    }

    private fun byteHex(bits: String): String {
        require(bits.length == 8 && bits.all { it == '0' || it == '1' }) {
            "状态必须是 8 位 0/1"
        }
        return bits.toInt(2).toString(16).uppercase().padStart(2, '0')
    }

    private fun String.bit(index: Int): Boolean = getOrNull(index) == '1'

    private fun podgFuncType(abilityByte: String): Int? = when {
        abilityByte.bit(6) -> 4
        abilityByte.bit(7) -> 3
        else -> null
    }
}
