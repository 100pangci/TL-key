package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.protocol.BleCredential
import org.json.JSONArray
import org.json.JSONObject

/**
 * 车辆模型：登录、缓存、多车切换、BLE 鉴权与控制需要的字段。
 *
 * 服务器有两个数据源：
 * - `userCarList`：车辆列表，通常没有 mac/passwordInfo；
 * - `carStatus`：当前使用车辆的完整信息，含 mac 与 passwordInfo。
 * 合并时以列表刷新名称/车型，同时保留已缓存的 BLE 凭据，做到切换已缓存车辆时不联网。
 */
data class Vehicle(
    val id: String,
    val name: String,
    val carType: String,
    val modelType: Int,
    val mac: String,
    val btName: String?,
    val btMac: String?,
    val btPin: String?,
    val passwordMain: String,
    val passwordChildren: List<String>,
    val shareCarFlag: Boolean,
    val frame: String,
    val imei: String,
    val userId: String,
    val isUsing: Boolean = false,
    /** carStatus 下发的 MQTT 云控连接参数；userCarList 通常没有，切换当前车后才会补齐。 */
    val mqHost: String = "",
    val mqPort: String = "",
    val mqUsername: String = "",
    val mqPassword: String = "",
    val imeiGps: String = "",
) {

    /**
     * 85 帧用服务器 mac；78 帧优先 btmac；QGJ 的 server mac 是车辆身份 MAC，
     * 真正的 BLE 地址是配对时扫描到的地址，因此优先 btmac。
     */
    val bleAddress: String?
        get() = when (modelType) {
            1, 2, 8, 283 -> btMac?.let(::formatMac) ?: formatMac(mac)
            else -> formatMac(mac) ?: btMac?.let(::formatMac)
        }

    /** 能否直接发起 BLE 控制；已缓存密码和地址的车辆切换后完全离线。 */
    val hasBleCredentials: Boolean
        get() = modelType != 0 && bleAddress != null &&
            (passwordMain.isNotBlank() || passwordChildren.isNotEmpty() || modelType == 1 || modelType == 2)

    /** carStatus 已下发 broker 与账号时才允许云控。 */
    val hasMqttCredentials: Boolean
        get() = mqHost.isNotBlank() && mqUsername.isNotBlank()

    val protocolLabel: String
        get() = when (modelType) {
            1 -> "KKS · 78 帧"
            2 -> "YJ · 78 帧"
            3 -> "BB · 85 帧"
            10, 14 -> "C39 · 85 帧"
            401 -> "BGPSBB · 85 帧"
            928 -> "BGPSJW · 85 帧"
            1501 -> "JD · 85 帧"
            1601 -> "AX · 85 帧"
            1701 -> "HJ · 85 帧"
            2103 -> "BGPSXL · 85 帧"
            2201 -> "BGPSYY · 85 帧"
            8, 283 -> "QGJ · Kuyi"
            else -> "modelType $modelType"
        }

    fun credential(uid: String): BleCredential = BleCredential(
        password = passwordMain,
        uid = uid,
        isShared = shareCarFlag,
        childPassword = passwordChildren.firstOrNull(),
    )

    /**
     * 用服务器列表项刷新展示字段，但绝不丢掉本地已缓存的 BLE 凭据。
     * `userCarList` 通常不返回 mac/passwordInfo，直接覆盖会导致已配对车辆无法离线控制。
     */
    fun mergedWith(fresh: Vehicle): Vehicle {
        require(fresh.id == id) { "vehicle id mismatch" }
        return copy(
            name = fresh.name.ifBlank { name },
            carType = fresh.carType.ifBlank { carType },
            modelType = if (fresh.modelType != 0) fresh.modelType else modelType,
            mac = fresh.mac.ifBlank { mac },
            btName = fresh.btName ?: btName,
            btMac = fresh.btMac ?: btMac,
            btPin = fresh.btPin ?: btPin,
            passwordMain = fresh.passwordMain.ifBlank { passwordMain },
            passwordChildren = fresh.passwordChildren.ifEmpty { passwordChildren },
            shareCarFlag = fresh.shareCarFlag || shareCarFlag,
            frame = fresh.frame.ifBlank { frame },
            imei = fresh.imei.ifBlank { imei },
            userId = fresh.userId.ifBlank { userId },
            isUsing = fresh.isUsing || isUsing,
            mqHost = fresh.mqHost.ifBlank { mqHost },
            mqPort = fresh.mqPort.ifBlank { mqPort },
            mqUsername = fresh.mqUsername.ifBlank { mqUsername },
            mqPassword = fresh.mqPassword.ifBlank { mqPassword },
            imeiGps = fresh.imeiGps.ifBlank { imeiGps },
        )
    }

    fun toJson(): String = JSONObject().apply {
        put("carId", id)
        put("carName", name)
        put("carType", carType)
        put("modelType", modelType)
        put("mac", mac)
        put("btname", btName.orEmpty())
        put("btmac", btMac.orEmpty())
        put("btpin", btPin.orEmpty())
        put("shareCarFlag", shareCarFlag)
        put("frame", frame)
        put("imei", imei)
        put("imeiGps", imeiGps)
        put("userId", userId)
        put("isUsing", isUsing)
        put("mqHost", mqHost)
        put("mqPort", mqPort)
        put("mqUsername", mqUsername)
        put("mqPassword", mqPassword)
        put(
            "passwordInfo",
            JSONObject().apply {
                put("main", passwordMain)
                put("children", JSONArray(passwordChildren))
            },
        )
    }.toString()

    companion object {

        fun fromJson(raw: String): Vehicle = fromStored(JSONObject(raw))

        fun fromJsonArray(raw: String): List<Vehicle> {
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    runCatching { fromStored(item) }.getOrNull()?.let(::add)
                }
            }
        }

        fun toJsonArray(vehicles: List<Vehicle>): String {
            val array = JSONArray()
            vehicles.forEach { array.put(JSONObject(it.toJson())) }
            return array.toString()
        }

        private fun fromStored(root: JSONObject): Vehicle {
            val passwordInfo = root.optJSONObject("passwordInfo")
            return Vehicle(
                id = root.string("carId"),
                name = root.string("carName"),
                carType = root.string("carType"),
                modelType = root.optInt("modelType", 0),
                mac = root.string("mac"),
                btName = root.string("btname").takeIf { it.isNotBlank() },
                btMac = root.string("btmac").takeIf { it.isNotBlank() },
                btPin = root.string("btpin").takeIf { it.isNotBlank() },
                passwordMain = passwordInfo?.string("main").orEmpty(),
                passwordChildren = passwordInfo?.stringList("children").orEmpty(),
                shareCarFlag = root.boolean("shareCarFlag"),
                frame = root.string("frame"),
                imei = root.string("imei"),
                userId = root.string("userId"),
                isUsing = root.boolean("isUsing"),
                mqHost = root.string("mqHost"),
                mqPort = root.string("mqPort"),
                mqUsername = root.string("mqUsername"),
                mqPassword = root.string("mqPassword"),
                imeiGps = root.string("imeiGps"),
            )
        }

        /** carStatus 的 data 对象。 */
        fun fromCarStatus(data: JSONObject): Vehicle {
            val passwordInfo = data.optJSONObject("passwordInfo")
            return Vehicle(
                id = data.string("carId"),
                name = data.string("carName").ifBlank { data.string("carNickName") },
                carType = data.string("carType"),
                modelType = data.optInt("modelType", 0),
                mac = data.string("mac"),
                btName = data.string("btname").takeIf { it.isNotBlank() },
                btMac = data.string("btmac").takeIf { it.isNotBlank() },
                btPin = data.string("btpin").takeIf { it.isNotBlank() },
                passwordMain = passwordInfo?.string("main").orEmpty(),
                passwordChildren = passwordInfo?.stringList("children").orEmpty(),
                shareCarFlag = data.boolean("shareCarFlag"),
                frame = data.string("frame"),
                imei = data.string("imei"),
                userId = data.string("userId"),
                isUsing = data.boolean("isUsing"),
                mqHost = data.string("mqHost"),
                mqPort = data.string("mqPort"),
                mqUsername = data.string("mqUsername"),
                mqPassword = data.string("mqPassword"),
                imeiGps = data.string("imeiGps"),
            )
        }

        /** userCarList / userCarPage 列表项（通常不含 passwordInfo）。 */
        fun fromListItem(item: JSONObject): Vehicle = Vehicle(
            id = item.string("carId"),
            name = item.string("carName").ifBlank { item.string("carNickName") },
            carType = item.string("carType"),
            modelType = item.optInt("modelType", 0),
            mac = item.string("mac"),
            btName = item.string("btname").takeIf { it.isNotBlank() },
            btMac = item.string("btmac").takeIf { it.isNotBlank() },
            btPin = item.string("btpin").takeIf { it.isNotBlank() },
            passwordMain = "",
            passwordChildren = emptyList(),
            shareCarFlag = item.boolean("shareCarFlag"),
            frame = item.string("frame"),
            imei = item.string("imei"),
            userId = item.string("userId"),
            isUsing = item.boolean("isUsing"),
            mqHost = item.string("mqHost"),
            mqPort = item.string("mqPort"),
            mqUsername = item.string("mqUsername"),
            mqPassword = item.string("mqPassword"),
            imeiGps = item.string("imeiGps"),
        )

        fun formatMac(raw: String): String? {
            val clean = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.uppercase()
            if (clean.length != 12) return null
            return clean.chunked(2).joinToString(":")
        }

        private fun JSONObject.string(name: String): String =
            if (has(name) && !isNull(name)) optString(name) else ""

        private fun JSONObject.boolean(name: String): Boolean =
            if (has(name) && !isNull(name)) optBoolean(name, false) else false

        private fun JSONObject.stringList(name: String): List<String> {
            val array = optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }
    }
}
