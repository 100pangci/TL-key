package com.ywpc.tlkey.vehicle

import org.json.JSONObject

/**
 * `app/car/getCarInfo` 返回的车辆档案。
 *
 * 服务器绑定接口要求提交完整的 CarInfoBean，所以保留原始 JSON，绑定请求原样回传；
 * 同时解析出客户端分流需要的字段。
 */
data class CarInfo(
    val raw: JSONObject,
    val carId: String,
    val carName: String,
    val carType: String,
    val frame: String,
    val imei: String,
    val modelType: Int,
    val isBluetooth: String,
    val isTelligence: String,
    val status: String,
    val phone: String,
    val storePhone: String,
    val fmac: String,
    val isBindStore: String,
    val isCheck: String,
) {

    val isSmart: Boolean
        get() = isTelligence != "0"

    /**
     * 官方 BindCarDetailFragment 分支：isTelligence=="0" 直接走成功页，
     * 否则 isBluetooth=="1" 进 BLE 搜索；isBluetooth!="1" 时仅 bikeBind.modelType==8 特殊进 BLE。
     * 283 仍依赖服务器返回的 isBluetooth=="1"。
     */
    val needsBleBind: Boolean
        get() = isSmart && (isBluetooth == "1" || modelType == 8)

    /**
     * 绑定前检查。返回 null 表示可以继续；返回文案表示需要先处理。
     * 顺序对齐官方 BindCarDetailFragment 的决策树。
     */
    fun precheckError(accountPhone: String?): String? {
        if (!isSmart) return null
        if (needsBleBind) return null
        if (imei.isBlank()) return "该车需要补充 IMEI，暂不支持在此绑定"
        if (phone.isNotBlank() && accountPhone != null && phone != accountPhone) {
            return "该车已被其他手机号绑定"
        }
        if (status == "1") return "该车已绑定，请在车辆列表中刷新"
        return null
    }

    companion object {
        val BLE_MODEL_TYPES = setOf(1, 2, 3, 8, 10, 14, 283, 401, 928, 1501, 1601, 1701, 2103, 2201)

        fun fromJson(data: JSONObject): CarInfo {
            val copy = JSONObject(data.toString())
            return CarInfo(
                raw = copy,
                carId = copy.string("carId"),
                carName = copy.string("carName").ifBlank { copy.string("carNickName") },
                carType = copy.string("carType"),
                frame = copy.string("frame"),
                imei = copy.string("imei"),
                modelType = copy.optInt("modelType", 0),
                isBluetooth = copy.string("isBluetooth"),
                isTelligence = copy.string("isTelligence"),
                status = copy.string("status"),
                phone = copy.string("phone"),
                storePhone = copy.string("storePhone"),
                fmac = copy.string("fmac"),
                isBindStore = copy.string("isBindStore"),
                isCheck = copy.string("isCheck"),
            )
        }

        private fun JSONObject.string(name: String): String =
            if (has(name) && !isNull(name)) optString(name) else ""
    }
}

/** `app/v3/vehicle/bind` / `bikeBindAgain` / `bindAgain` 的返回。 */
data class BikeBind(
    val carId: String,
    val carName: String,
    val carNickName: String,
    val carType: String,
    val frame: String,
    val mac: String,
    val imei: String,
    val modelType: Int,
    val isBindStore: String,
    val isCheck: String,
    val passwordMain: String,
    val passwordChildren: List<String>,
) {

    fun toVehicle(
        uid: String?,
        btName: String? = null,
        btMac: String? = null,
        fallbackMac: String = "",
        fallbackFrame: String = "",
        fallbackName: String = "",
    ): Vehicle = Vehicle(
        id = carId,
        name = carName.ifBlank { carNickName }.ifBlank { fallbackName },
        carType = carType,
        modelType = modelType,
        mac = mac.ifBlank { fallbackMac },
        btName = btName,
        btMac = btMac ?: mac.takeIf { it.isNotBlank() },
        btPin = null,
        passwordMain = passwordMain,
        passwordChildren = passwordChildren,
        shareCarFlag = false,
        frame = frame.ifBlank { fallbackFrame },
        imei = imei,
        userId = uid.orEmpty(),
    )

    companion object {
        fun fromJson(data: JSONObject): BikeBind {
            val password = data.optJSONObject("passwordInfo")
            return BikeBind(
                carId = data.string("carId"),
                carName = data.string("carName"),
                carNickName = data.string("carNickName"),
                carType = data.string("carType"),
                frame = data.string("frame"),
                mac = data.string("mac"),
                imei = data.string("imei"),
                modelType = data.optInt("modelType", 0),
                isBindStore = data.string("isBindStore"),
                isCheck = data.string("isCheck"),
                passwordMain = password?.string("main").orEmpty(),
                passwordChildren = password?.stringList("children").orEmpty(),
            )
        }

        private fun JSONObject.string(name: String): String =
            if (has(name) && !isNull(name)) optString(name) else ""

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

/** `app/v3/active/get` 的 ECU 激活状态。 */
data class EcuGet(
    val isActivate: Int,
    val activateAgain: Int,
    val num: Int,
) {
    companion object {
        fun fromJson(data: JSONObject): EcuGet = EcuGet(
            isActivate = data.optInt("isActivate", 1),
            activateAgain = data.optInt("activateAgain", 0),
            num = data.optInt("num", 0),
        )
    }
}
