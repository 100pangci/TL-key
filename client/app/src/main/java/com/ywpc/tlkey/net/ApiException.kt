package com.ywpc.tlkey.net

class ApiException(
    message: String,
    val code: String? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val isTokenExpired: Boolean
        get() = httpStatus == 401 || code == "401" || code == "TOKEN_EXPIRED"
}
