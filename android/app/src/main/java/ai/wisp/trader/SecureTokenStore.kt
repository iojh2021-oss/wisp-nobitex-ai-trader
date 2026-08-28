package ai.wisp.trader

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores user-provided API secrets encrypted with an Android Keystore AES key. */
class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
    private val keyAlias = "wisp_local_secrets_key"
    private val keyStoreName = "AndroidKeyStore"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(keyStoreName).apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", keyStoreName)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveValue(name: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    private fun readValue(name: String): String {
        val encoded = prefs.getString(name, null) ?: return ""
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > 12)
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrElse {
            prefs.edit().remove(name).apply()
            ""
        }
    }

    fun saveOpenAiKey(value: String) = saveValue("openai_api_key", value)
    fun readOpenAiKey(): String = readValue("openai_api_key")
    fun clearOpenAiKey() = prefs.edit().remove("openai_api_key").apply()

    fun saveNobitexToken(value: String) = saveValue("nobitex_api_token", value)
    fun readNobitexToken(): String = readValue("nobitex_api_token")
    fun clearNobitexToken() = prefs.edit().remove("nobitex_api_token").apply()

    fun saveNobitexApiKey(value: String) = saveValue("nobitex_api_key", value)
    fun readNobitexApiKey(): String = readValue("nobitex_api_key")
    fun clearNobitexApiKey() = prefs.edit().remove("nobitex_api_key").apply()

    fun saveNobitexPrivateKey(value: String) = saveValue("nobitex_private_key", value)
    fun readNobitexPrivateKey(): String = readValue("nobitex_private_key")
    fun clearNobitexPrivateKey() = prefs.edit().remove("nobitex_private_key").apply()

    fun saveCoinStatsKey(value: String) = saveValue("coinstats_api_key", value)
    fun readCoinStatsKey(): String = readValue("coinstats_api_key")
    fun clearCoinStatsKey() = prefs.edit().remove("coinstats_api_key").apply()

    // Kept for compatibility with older APK builds.
    fun save(token: String) = saveOpenAiKey(token)
    fun read(): String = readOpenAiKey()
    fun clear() = clearOpenAiKey()
}
