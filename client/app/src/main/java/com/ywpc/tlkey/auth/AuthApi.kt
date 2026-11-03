package com.ywpc.tlkey.auth

import com.ywpc.tlkey.net.ApiException
import com.ywpc.tlkey.net.HttpClient
import org.json.JSONObject

data class LoginResult(
    val token: String,
    val uid: String,
)

data class SliderVerifyResult(
    val passToken: String,
    val captchaCode: String,
    val captchaMsg: String,
    val expireIn: Int,
)

data class SmsSendResult(
    val code: String,
    val message: String,
    val data: String,
    val httpStatus: Int,
)

data class SmsRequestResult(
    val slider: SliderVerifyResult,
    val send: SmsSendResult,
)

class AuthApi(private val http: HttpClient) {

    suspend fun sliderVerify(ticket: String, randstr: String): SliderVerifyResult {
        val body = JSONObject()
            .put("ticket", ticket)
            .put("randstr", randstr)
        val response = http.post("app/captcha/slider/verify", body)
        val data = response.dataObject()
        val passed = when (val value = data.opt("passed")) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            is Number -> value.toInt() == 1
            else -> false
        }
        val passToken = data.string("captchaPassToken").trim()
        val captchaCode = data.string("captchaCode")
        val captchaMsg = data.string("captchaMsg")
        if (!passed || passToken.isBlank()) {
            throw ApiException(
                captchaMsg.ifBlank { "滑块校验未通过" },
                code = captchaCode,
            )
        }
        val expireIn = when (val value = data.opt("captchaPassTokenExpireIn")) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
        return SliderVerifyResult(passToken, captchaCode, captchaMsg, expireIn)
    }

    suspend fun sendLoginCode(phone: String, captchaPassToken: String): SmsSendResult {
        val response = http.postQuery(
            "app/sms/loginCode",
            mapOf(
                "phone" to phone,
                "captchaPassToken" to captchaPassToken,
            ),
        )
        val json = response.json
        val data = when (val value = json.opt("data")) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> ""
        }
        return SmsSendResult(
            code = json.string("code"),
            message = json.string("msg"),
            data = data,
            httpStatus = response.httpStatus,
        )
    }

    suspend fun login(macCode: String, phone: String, smsCode: String): LoginResult {
        val body = JSONObject()
            .put("macCode", macCode)
            .put("phone", phone)
            .put("smsCode", smsCode)
            .put("autoCompleteUserDetail", "true")
        val response = http.post("app/login", body)
        val token = response.authorization?.takeIf { it.isNotBlank() }
            ?: throw ApiException("登录成功但服务器没有返回 Authorization")
        val uid = response.json.optJSONObject("data")?.string("uid").orEmpty()
        return LoginResult(token, uid)
    }

    private fun JSONObject.string(name: String): String =
        if (has(name) && !isNull(name)) optString(name) else ""
}
