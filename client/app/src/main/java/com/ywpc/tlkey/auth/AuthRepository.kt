package com.ywpc.tlkey.auth

import android.content.Context
import com.ywpc.tlkey.net.ApiException

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) {

    suspend fun requestSms(phone: String, ticket: String, randstr: String): SmsRequestResult {
        val slider = authApi.sliderVerify(ticket, randstr)
        val send = authApi.sendLoginCode(phone, slider.passToken)
        return SmsRequestResult(slider, send)
    }

    suspend fun login(context: Context, phone: String, smsCode: String): LoginResult {
        val result = authApi.login(MacCode.get(context), phone, smsCode)
        tokenStore.save(result.token, result.uid.takeIf { it.isNotBlank() })
        return result
    }

    fun ensureUid(uid: String) {
        if (uid.isNotBlank() && tokenStore.uid().isNullOrBlank()) {
            tokenStore.saveUid(uid)
        }
    }

    fun currentUid(): String? = tokenStore.uid()

    fun getValidToken(): String =
        tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw ApiException("登录已失效，请重新登录", code = "TOKEN_EXPIRED")

    fun hasToken(): Boolean = tokenStore.hasToken()

    fun logout() = tokenStore.clear()
}
