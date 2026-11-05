package com.ywpc.tlkey.cloud

import com.ywpc.tlkey.BuildConfig
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.vehicle.Vehicle

/**
 * 官方 3.6.2 云控连接参数。
 *
 * 来源：`model/home/mqtt/MqttUtil.java`、`TailgMqttUtil.java`、
 * `model/home/fragment/ControlFragment.java`（initMqtt / reConn / mqttPublish）。
 *
 * - KKS / YJ（modelType 1 / 2）：broker 与账号是 APK 内置的，topic 用车辆 imei。
 * - QGJ / C18 / BGPS（其它 modelType）：broker 与账号来自 carStatus 的
 *   mqHost / mqPort / mqUsername / mqPassword，topic 用 imeiGps，clientId 带 uid。
 */
data class MqttEndpoint(
    val host: String,
    val username: String,
    val password: String,
    val clientId: String,
    val imei: String,
    val subscribeTopics: List<String>,
    val publishTopic: String,
    val tls: Boolean,
) {
    /**
     * 判断能否复用已有连接。clientId 每次 resolve 都带随机后缀，
     * 只用于避免 broker 端的 clientId 冲突，不代表连接目标不同。
     */
    fun sameConnectionTarget(other: MqttEndpoint): Boolean =
        host == other.host &&
            username == other.username &&
            password == other.password &&
            imei == other.imei &&
            subscribeTopics == other.subscribeTopics &&
            publishTopic == other.publishTopic &&
            tls == other.tls
}

enum class MqttUnavailableReason {
    UNSUPPORTED_MODEL,
    MISSING_IMEI,
    MISSING_IMEI_GPS,
    MISSING_CREDENTIALS,
    MISSING_UID,
}

sealed interface MqttEndpointResult {
    data class Ready(val endpoint: MqttEndpoint) : MqttEndpointResult
    data class Unavailable(val reason: MqttUnavailableReason) : MqttEndpointResult
}

object MqttEndpoints {

    const val KKS_YJ_HOST = "tcp://www.tailgdd.com:1883"
    const val C18_HOST = "ssl://www.tailgdd.com:6668"

    // 官方 APK 内置的 KKS/YJ 共享 broker 账号；仓库不保存该凭据。
    // 构建时通过 Gradle 属性或环境变量提供，未配置时 KKS/YJ 云控返回 MISSING_CREDENTIALS。
    private val LEGACY_USER: String get() = BuildConfig.MQTT_LEGACY_USER
    private val LEGACY_PASSWORD: String get() = BuildConfig.MQTT_LEGACY_PASSWORD

    fun resolve(
        vehicle: Vehicle?,
        uid: String,
        randomSuffix: String = randomSuffix(),
        legacyUser: String = LEGACY_USER,
        legacyPassword: String = LEGACY_PASSWORD,
    ): MqttEndpointResult {
        if (vehicle == null || vehicle.modelType == 0) {
            return MqttEndpointResult.Unavailable(MqttUnavailableReason.UNSUPPORTED_MODEL)
        }
        return when (vehicle.modelType) {
            1, 2 -> legacyEndpoint(vehicle, randomSuffix, legacyUser, legacyPassword)
            else -> c18Endpoint(vehicle, uid, randomSuffix)
        }
    }

    private fun legacyEndpoint(
        vehicle: Vehicle,
        randomSuffix: String,
        legacyUser: String,
        legacyPassword: String,
    ): MqttEndpointResult {
        val imei = vehicle.imei.trim()
        if (imei.isEmpty()) {
            return MqttEndpointResult.Unavailable(MqttUnavailableReason.MISSING_IMEI)
        }
        if (legacyUser.isBlank() || legacyPassword.isBlank()) {
            return MqttEndpointResult.Unavailable(MqttUnavailableReason.MISSING_CREDENTIALS)
        }
        val name = if (vehicle.modelType == 2) "yunjia" else "kks"
        return MqttEndpointResult.Ready(
            MqttEndpoint(
                host = KKS_YJ_HOST,
                username = legacyUser,
                password = legacyPassword,
                clientId = "app_$imei$randomSuffix",
                imei = imei,
                subscribeTopics = listOf("$name-get-$imei"),
                publishTopic = "app-update-$name/$imei",
                tls = false,
            ),
        )
    }

    private fun c18Endpoint(vehicle: Vehicle, uid: String, randomSuffix: String): MqttEndpointResult {
        val imeiGps = vehicle.imeiGps.trim()
        val username = vehicle.mqUsername.trim()
        val password = vehicle.mqPassword
        if (imeiGps.isEmpty()) {
            return MqttEndpointResult.Unavailable(MqttUnavailableReason.MISSING_IMEI_GPS)
        }
        if (username.isEmpty() || password.isEmpty()) {
            return MqttEndpointResult.Unavailable(MqttUnavailableReason.MISSING_CREDENTIALS)
        }
        if (uid.isBlank()) {
            return MqttEndpointResult.Unavailable(MqttUnavailableReason.MISSING_UID)
        }

        val host = if (vehicle.mqHost.isNotBlank() && vehicle.mqPort.isNotBlank()) {
            "ssl://${vehicle.mqHost.trim()}:${vehicle.mqPort.trim()}"
        } else {
            C18_HOST
        }
        val topics = buildList {
            add("S_APP/STATUS/$imeiGps")
            // 官方 conSuccess/reConn 在非 KKS/YJ 车型上都会订阅 OTA 主题。
            add("S_APP/OTA/$imeiGps")
            if (vehicle.modelType == 10 || vehicle.modelType == 14) {
                add("S_APP/CHARGER/$imeiGps")
                add("S_APP/CHECK/$imeiGps")
            }
        }
        return MqttEndpointResult.Ready(
            MqttEndpoint(
                host = host,
                username = username,
                password = password,
                clientId = "app_${imeiGps}_${uid}_android_$randomSuffix",
                imei = imeiGps,
                subscribeTopics = topics,
                publishTopic = "APP_S/CMD/$imeiGps",
                tls = true,
            ),
        )
    }

    /** 官方 `Util.getRandom(3)`：3 位随机数字，仅用于避免 clientId 冲突。 */
    fun randomSuffix(): String = (0..999).random().toString().padStart(3, '0')
}

/** 官方 MqttPayloadBean 的子集；缺省字段为 null，便于按消息增量合并。 */
data class MqttStatus(
    val acc: String? = null,
    val defenceStatus: String? = null,
    val accErrorStatus: Int? = null,
    val defenceErrorStatus: Int? = null,
    val muteStatus: Int? = null,
    val speed: Int? = null,
    val seq: Int? = null,
) {

    val accOn: Boolean? get() = acc?.let { it == "1" }
    val locked: Boolean? get() = defenceStatus?.let { it == "1" }
    val muted: Boolean? get() = muteStatus?.let { it == 1 }

    fun mergedWith(fresh: MqttStatus): MqttStatus = MqttStatus(
        acc = fresh.acc ?: acc,
        defenceStatus = fresh.defenceStatus ?: defenceStatus,
        accErrorStatus = fresh.accErrorStatus ?: accErrorStatus,
        defenceErrorStatus = fresh.defenceErrorStatus ?: defenceErrorStatus,
        muteStatus = fresh.muteStatus ?: muteStatus,
        speed = fresh.speed ?: speed,
        seq = fresh.seq ?: seq,
    )
}

/** 云控指令。wire 值来自官方 TailgMqttUtil.MqttCmd。 */
enum class CloudCommand(
    val wire: String,
    val label: String,
    val needsAck: Boolean,
) {
    UNLOCK("unlock", "开锁", true),
    LOCK("lock", "上锁", true),
    POWER_ON("start", "上电", true),
    POWER_OFF("stop", "断电", true),
    FIND("search", "寻车", false),
    SEAT("openCushion", "开座桶", false),
    MUTE("mute", "静音", false),
    UNMUTE("unmute", "取消静音", false),
    REFRESH("searchDeviceInfo", "刷新状态", false),
    ;

    /** 回执是否代表该指令成功；null 表示该指令没有独立回执字段。 */
    fun successOf(status: MqttStatus): Boolean? = when (this) {
        POWER_ON -> status.accOn
        POWER_OFF -> status.accOn?.let { !it }
        LOCK -> status.locked
        UNLOCK -> status.locked?.let { !it }
        else -> null
    }

    fun errorCodeOf(status: MqttStatus): Int? = when (this) {
        POWER_ON, POWER_OFF -> status.accErrorStatus?.takeIf { it != 0 }
        LOCK, UNLOCK -> status.defenceErrorStatus?.takeIf { it != 0 }
        else -> null
    }
}

/** BLE 控制动作与云控指令一一对应。 */
fun ControlAction.toCloudCommand(): CloudCommand = when (this) {
    ControlAction.UNLOCK -> CloudCommand.UNLOCK
    ControlAction.LOCK -> CloudCommand.LOCK
    ControlAction.POWER_ON -> CloudCommand.POWER_ON
    ControlAction.POWER_OFF -> CloudCommand.POWER_OFF
    ControlAction.FIND -> CloudCommand.FIND
    ControlAction.SEAT -> CloudCommand.SEAT
}

object MqttPayload {

    fun command(imei: String, command: String): String =
        """{"imei":${quote(imei)},"command":${quote(command)}}"""

    /**
     * 官方用 Gson 解析 MqttPayloadBean；字段名大小写必须保持 ACC。
     * 这里只解析扁平 JSON 对象，避免在 JVM 单测里依赖 Android 的 org.json。
     */
    fun parse(raw: String): MqttStatus? {
        val json = parseFlatObject(raw) ?: return null
        return MqttStatus(
            acc = json["ACC"],
            defenceStatus = json["defenceStatus"],
            accErrorStatus = json["accErrorStatus"]?.toIntOrNull(),
            defenceErrorStatus = json["defenceErrorStatus"]?.toIntOrNull(),
            muteStatus = json["muteStatus"]?.toIntOrNull(),
            speed = json["speed"]?.toIntOrNull(),
            seq = json["seq"]?.toIntOrNull(),
        )
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun parseFlatObject(raw: String): Map<String, String>? {
        val text = raw.trim()
        if (text.length < 2 || !text.startsWith("{") || !text.endsWith("}")) return null
        var index = 1
        val result = LinkedHashMap<String, String>()

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseString(): String? {
            if (index >= text.length || text[index] != '"') return null
            index++
            val sb = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) return null
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> sb.append(escaped)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) return null
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: return null
                                index += 4
                                sb.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }

        skipWhitespace()
        if (index < text.length && text[index] == '}') return result

        while (index < text.length) {
            skipWhitespace()
            val key = parseString() ?: return null
            skipWhitespace()
            if (index >= text.length || text[index] != ':') return null
            index++
            skipWhitespace()
            val value = if (index < text.length && text[index] == '"') {
                parseString() ?: return null
            } else {
                val start = index
                while (index < text.length && text[index] != ',' && text[index] != '}') index++
                text.substring(start, index).trim()
            }
            result[key] = value
            skipWhitespace()
            if (index >= text.length) return null
            when (text[index]) {
                ',' -> index++
                '}' -> return result
                else -> return null
            }
        }
        return null
    }
}
