package com.ywpc.tlkey.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感数据：服务器 Token、uid、车辆缓存（含 BLE 密码）。
 * 使用 Android Keystore 的 AES-GCM 加密后保存到私有 SharedPreferences。
 */
class SecureStorage(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("secure_storage", Context.MODE_PRIVATE)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    var token: String?
        get() = getString(KEY_TOKEN)
        set(value) = putString(KEY_TOKEN, value)

    var uid: String?
        get() = getString(KEY_UID)
        set(value) = putString(KEY_UID, value)

    /** 旧版单车缓存，仅用于迁移。 */
    var vehicleJson: String?
        get() = getString(KEY_VEHICLE)
        set(value) = putString(KEY_VEHICLE, value)

    /** 多车缓存：JSON 数组，每项是一个完整 Vehicle（含 BLE 密码）。 */
    var vehiclesJson: String?
        get() = getString(KEY_VEHICLES)
        set(value) = putString(KEY_VEHICLES, value)

    var activeVehicleId: String?
        get() = getString(KEY_ACTIVE_VEHICLE)
        set(value) = putString(KEY_ACTIVE_VEHICLE, value)

    fun hasSession(): Boolean = !token.isNullOrBlank()

    fun clearSession() {
        sp.edit().clear().apply()
    }

    private fun putString(name: String, value: String?) {
        if (value == null) {
            sp.edit().remove(name).apply()
            return
        }
        val encrypted = try {
            encrypt(value)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt failed", t)
            null
        }
        if (encrypted == null) {
            sp.edit().remove(name).apply()
        } else {
            sp.edit().putString(name, encrypted).apply()
        }
    }

    private fun getString(name: String): String? {
        val stored = sp.getString(name, null) ?: return null
        return try {
            decrypt(stored)
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed", t)
            null
        }
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + body.size)
        iv.copyInto(combined, 0)
        body.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        require(combined.size > IV_SIZE) { "invalid ciphertext" }
        val iv = combined.copyOfRange(0, IV_SIZE)
        val body = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "SecureStorage"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tlkey_secure_storage_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_TOKEN = "token"
        const val KEY_UID = "uid"
        const val KEY_VEHICLE = "vehicle"
        const val KEY_VEHICLES = "vehicles"
        const val KEY_ACTIVE_VEHICLE = "active_vehicle"
    }
}
