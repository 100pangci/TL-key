package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.net.HttpClient
import org.json.JSONObject

/**
 * 官方 3.6.2 绑车接口。所有请求都带用户自己的 Authorization Token，
 * 不伪造 captcha/绑定凭证，只走官方正常流程。
 */
class BindApi(private val http: HttpClient) {

    suspend fun getCarInfo(token: String, barCode: String): CarInfo {
        val response = http.post(
            "app/car/getCarInfo",
            JSONObject().put("barCode", barCode),
            token,
        )
        return CarInfo.fromJson(response.dataObject())
    }

    /** 3.6.2 主链路：提交 getCarInfo 的原始 JSON。 */
    suspend fun bind(token: String, carInfo: CarInfo): BikeBind {
        val response = http.post("app/v3/vehicle/bind", carInfo.raw, token)
        return BikeBind.fromJson(response.dataObject())
    }

    /** modelType 3 / 8：回写真实 MAC。 */
    suspend fun bikeBindAgain(token: String, body: JSONObject): BikeBind {
        val response = http.post("app/car/bikeBindAgain", body, token)
        return BikeBind.fromJson(response.dataObject())
    }

    /** modelType 10/14：额外回写 code。 */
    suspend fun bikeBindAgainC39(token: String, body: JSONObject): BikeBind {
        val response = http.post("app/car/bikeBindAgain", body, token)
        return BikeBind.fromJson(response.dataObject())
    }

    /** V3 / QGJ 283：额外回写 IMEI、code。 */
    suspend fun bindAgain(token: String, body: JSONObject): BikeBind {
        val response = http.post("app/car/bindAgain", body, token)
        return BikeBind.fromJson(response.dataObject())
    }

    /**
     * RESET 后的服务器确认。官方方法名是 updateQgjCarInfo，但 3.6.2 的
     * Retrofit 注解路径是 `app/car/updateCarInfo`；返回体里的 frame 用于下一步 activeGet。
     */
    suspend fun updateCarInfo(
        token: String,
        carId: String,
        perfectStatus: String = "1",
    ): JSONObject? {
        val response = http.post(
            "app/car/updateCarInfo",
            JSONObject()
                .put("carId", carId)
                .put("perfectStatus", perfectStatus),
            token,
        )
        return runCatching { response.dataObject() }.getOrNull()
    }

    suspend fun activeGet(token: String, vin: String): EcuGet {
        val response = http.post(
            "app/v3/active/get",
            JSONObject().put("vin", vin),
            token,
        )
        return EcuGet.fromJson(response.dataObject())
    }
}
