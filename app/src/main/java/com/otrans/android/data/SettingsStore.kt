package com.otrans.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("otrans_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        lanServerUrl = preferences.getString("lan_url", "http://192.168.1.10:18990").orEmpty(),
        wanServerUrl = preferences.getString("wan_url", "").orEmpty(),
        serverId = preferences.getString("server_id", "").orEmpty(),
        deviceName = preferences.getString("device_name", "Android 手机").orEmpty(),
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString("lan_url", settings.lanServerUrl.trim().trimEnd('/'))
            .putString("wan_url", settings.wanServerUrl.trim().trimEnd('/'))
            .putString("server_id", settings.serverId)
            .putString("device_name", settings.deviceName.trim())
            .apply()
    }

    fun token(): String {
        val payload = preferences.getString("device_token", null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            val ivLength = bytes.first().toInt() and 0xff
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivLength))
            cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun setToken(value: String) {
        if (value.isBlank()) {
            preferences.edit().remove("device_token").apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        preferences.edit().putString("device_token", Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "otrans-device-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
