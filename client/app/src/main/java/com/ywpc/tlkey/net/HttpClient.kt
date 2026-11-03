package com.ywpc.tlkey.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 只访问官方 v1 API，使用系统默认 TLS 校验，不做任何 TrustManager 绕过。
 * 请求头与官方 TailgRetrofit.addHeaders() 对齐。
 */
class HttpClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun post(
        path: String,
        body: JSONObject,
        token: String? = null,
    ): ApiResponse = execute(path, body, token)

    suspend fun postQuery(
        path: String,
        query: Map<String, String>,
        token: String? = null,
    ): ApiResponse {
        // 用 OkHttp HttpUrl 编码查询参数，与官方 Retrofit @Query 的转义规则一致。
        val url = (BASE + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
        return execute(url, JSONObject(), token, includeBody = false)
    }

    private suspend fun execute(
        pathOrUrl: String,
        body: JSONObject,
        token: String?,
        includeBody: Boolean = true,
    ): ApiResponse = withContext(Dispatchers.IO) {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else BASE + pathOrUrl
        val requestBody = if (includeBody) {
            body.toString().toRequestBody(JSON)
        } else {
            // 官方查询型 POST 没有 body，也不带 Content-Type。
            ByteArray(0).toRequestBody(null)
        }
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Authorization", token.orEmpty())
            .header("Forward-Service-Ip", "localhost")
            .header("language", "zh_CN")
            // 不要设置自定义 User-Agent：网关对非 okhttp/* UA 会返回假 code=0，
            // 请求不会进入真实发码后端；OkHttp 默认 UA 才能触发真实校验。
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.string("msg")?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                throw ApiException(
                    message,
                    code = response.code.toString(),
                    httpStatus = response.code,
                )
            }
            if (json == null) throw ApiException("服务器返回格式异常")
            val code = json.string("code")
            if (code != "0") {
                val fallback = if (code.isBlank()) {
                    "服务器返回格式异常"
                } else {
                    "请求失败（$code）"
                }
                throw ApiException(
                    json.string("msg").ifBlank { fallback },
                    code = code.ifBlank { "MISSING_CODE" },
                )
            }
            ApiResponse(
                httpStatus = response.code,
                authorization = response.header("Authorization"),
                json = json,
            )
        }
    }

    private fun JSONObject.string(name: String): String =
        if (has(name) && !isNull(name)) optString(name) else ""

    companion object {
        const val BASE = "https://www.tailgdd.com/v1/api/"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class ApiResponse(
    val httpStatus: Int,
    val authorization: String?,
    val json: JSONObject,
) {
    fun dataObject(): JSONObject =
        json.optJSONObject("data") ?: throw ApiException("服务器返回数据为空")

    fun dataArray(): org.json.JSONArray? = json.optJSONArray("data")
}
