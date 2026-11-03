package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.net.HttpClient
import org.json.JSONObject

/**
 * 官方 `POST app/car/getAbility`，body `{"imei": "<imei>"}`。
 * 返回的 `ecu*Func` 为 1 时官方 UI 才显示对应设置项；为 0 时隐藏。
 */
class EcuApi(private val http: HttpClient) {

    suspend fun ability(token: String, imei: String): EcuAbility {
        val response = http.post(
            "app/car/getAbility",
            JSONObject().put("imei", imei),
            token,
        )
        val data = response.dataObject()
        return EcuAbility(
            tcs = data.flag("ecuTCSFunc"),
            sfr = data.flag("ecuSFRFunc"),
            pcp = data.flag("ecuPCPFunc"),
            hdc = data.flag("ecuHDCFunc"),
            cc = data.flag("ecuCCFunc"),
            reverse = data.flag("ecuReverseFunc"),
            sfs = data.flag("ecuSFSFunc"),
            er = data.flag("ecuERFunc"),
            lbcm = data.flag("ecuLBCMFunc"),
            podg = data.flag("ecuPodgFunc"),
            hillParking = data.flag("ecuHillParkingFunc"),
            slipSlope = data.flag("ecuSlipSlopeFunc"),
            nitro = data.flag("ecuNitroFunc"),
            temp = data.flag("ecuTempFunc"),
            motorTemp = data.flag("ecuMotorTempFunc"),
            cgs = data.flag("ecuCgsFunc"),
        )
    }

    private fun JSONObject.flag(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optInt(name) == 1
    }
}

data class EcuAbility(
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
    val cgs: Boolean? = null,
) {
    fun supports(type: Int): Boolean? = when (type) {
        1 -> tcs
        2 -> sfr
        3 -> pcp
        4 -> hdc
        5 -> cc
        6 -> reverse
        7 -> sfs
        8 -> er
        9 -> lbcm
        10 -> podg
        11 -> hillParking
        12 -> slipSlope
        13 -> nitro
        14 -> temp
        15 -> motorTemp
        else -> null
    }
}
