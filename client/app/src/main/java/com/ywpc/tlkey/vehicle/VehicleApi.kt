package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.net.HttpClient
import org.json.JSONArray
import org.json.JSONObject

class VehicleApi(private val http: HttpClient) {

    suspend fun carStatus(token: String): Vehicle {
        val response = http.post(
            "app/centralControl/carStatus",
            JSONObject().put("phoneMode", android.os.Build.MODEL.ifBlank { "Android" }),
            token,
        )
        return Vehicle.fromCarStatus(response.dataObject())
    }

    /** 切换服务器端“当前使用车辆”，之后 carStatus 才会返回该车完整信息。 */
    suspend fun changeUsingCar(token: String, carId: String) {
        http.post(
            "app/centralControl/changeUsingCar",
            JSONObject().put("carId", carId),
            token,
        )
    }

    suspend fun userCarList(token: String): List<Vehicle> {
        val response = http.post(
            "app/userCarList",
            JSONObject().put("containShare", false),
            token,
        )
        val data = response.json.opt("data") ?: return emptyList()
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> {
                data.optJSONArray("list")
                    ?: data.optJSONArray("records")
                    ?: data.optJSONArray("data")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(Vehicle.fromListItem(item))
            }
        }
    }
}
