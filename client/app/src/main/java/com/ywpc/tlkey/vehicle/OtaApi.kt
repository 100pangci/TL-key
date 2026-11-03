package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 官方 OTA 云侧接口（`TailgService`）：
 * - `app/firmVersionInfo/getAllFirmVersion` body {imei, imeiGps}
 * - `app/firmVersionInfo/upGradation` body {id, imeiGps}
 * - `app/firmVersionInfo/otaProgress?imei=<imeiGps>`
 *
 * 这三条接口只负责查询版本、让服务器给车辆下发升级任务、查询进度；
 * 真正的固件下载与刷写由车辆/ECU 在后台完成（与官方主流程一致）。
 */
class OtaApi(private val http: HttpClient) {

    suspend fun versions(token: String, imei: String, imeiGps: String): OtaInfo {
        val response = http.post(
            "app/firmVersionInfo/getAllFirmVersion",
            JSONObject().put("imei", imei).put("imeiGps", imeiGps),
            token,
        )
        val data = response.dataObject()
        val array = data.optJSONArray("updateList") ?: JSONArray()
        val updates = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(OtaUpdate.fromJson(item))
            }
        }
        return OtaInfo(
            firmUpdate = data.optBoolean("firmUpdate", false),
            updates = updates,
        )
    }

    suspend fun trigger(token: String, updateId: String, imeiGps: String) {
        http.post(
            "app/firmVersionInfo/upGradation",
            JSONObject().put("id", updateId).put("imeiGps", imeiGps),
            token,
        )
    }

    suspend fun progress(token: String, imeiGps: String): OtaProgress? {
        val response = http.postQuery(
            "app/firmVersionInfo/otaProgress",
            mapOf("imei" to imeiGps),
            token,
        )
        val data = when (val raw = response.json.opt("data")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return null
        return OtaProgress(
            imei = data.optString("imei"),
            status = data.optIntOrNull("status"),
            type = data.optIntOrNull("type"),
            percent = data.optIntOrNull("upgradePercent"),
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null
}

data class OtaInfo(
    val firmUpdate: Boolean,
    val updates: List<OtaUpdate>,
) {
    val hasUpdate: Boolean
        get() = firmUpdate || updates.any { it.hasUpdate }
}

data class OtaUpdate(
    val id: String,
    val platform: String,
    val model: String,
    val type: String,
    val fileName: String,
    val isUpdate: String,
    val isForceUpdate: String,
    val remark: String,
    val versionName: String,
    val versionFullName: String,
    val downloadUrl: String,
    val fileHash: String,
) {
    val hasUpdate: Boolean
        get() = isUpdate == "1" || isUpdate.equals("true", ignoreCase = true)

    val forced: Boolean
        get() = isForceUpdate == "1" || isForceUpdate.equals("true", ignoreCase = true)

    companion object {
        fun fromJson(item: JSONObject): OtaUpdate = OtaUpdate(
            id = item.optString("id"),
            platform = item.optString("platform"),
            model = item.optString("model"),
            type = item.optString("type"),
            fileName = item.optString("fileName"),
            isUpdate = item.optString("isUpdate"),
            isForceUpdate = item.optString("isForceUpdate"),
            remark = item.optString("remark"),
            versionName = item.optString("versionName"),
            versionFullName = item.optString("versionFullName"),
            downloadUrl = item.optString("downloadUrl"),
            fileHash = item.optString("fileHash"),
        )
    }
}

data class OtaProgress(
    val imei: String,
    val status: Int?,
    val type: Int?,
    val percent: Int?,
)
