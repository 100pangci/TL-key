package com.ywpc.tlkey.auth

import com.ywpc.tlkey.storage.SecureStorage

class TokenStore(private val secure: SecureStorage) {

    fun token(): String? = secure.token

    fun uid(): String? = secure.uid

    fun save(token: String, uid: String?) {
        secure.token = token
        secure.uid = uid
    }

    fun saveUid(uid: String) {
        secure.uid = uid
    }

    fun clear() {
        secure.token = null
        secure.uid = null
    }

    fun hasToken(): Boolean = !secure.token.isNullOrBlank()
}
