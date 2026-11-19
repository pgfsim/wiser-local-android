package dev.wiserlocal.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Wiser account's refresh token between launches, encrypted with a key
 * held in the Android Keystore. The password is never stored, and the short-lived
 * access token stays in memory only.
 */
class CloudStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("wiser_cloud", Context.MODE_PRIVATE)

    /** Server address; only changed for testing against a stand-in server. */
    val base: String get() = sp.getString("base", null) ?: WiserCloud.DEFAULT_BASE

    var refreshToken: String?
        get() = sp.getString("refresh", null)?.let(::decrypt)
        set(v) = sp.edit().putString("refresh", v?.let(::encrypt)).apply()

    /** Name, email and so on, so they show before the first refresh. */
    var account: CloudAccount?
        get() = sp.getString("account", null)?.let { text ->
            runCatching {
                val o = JSONObject(text)
                fun f(name: String) = o.optString(name).takeIf { it.isNotEmpty() }
                CloudAccount(f("email"), f("firstName"), f("lastName"), f("countryCode"), f("timeZoneId"), f("hubModel"))
            }.getOrNull()
        }
        set(v) = sp.edit().putString(
            "account",
            v?.let {
                JSONObject()
                    .put("email", it.email).put("firstName", it.firstName).put("lastName", it.lastName)
                    .put("countryCode", it.countryCode).put("timeZoneId", it.timeZoneId).put("hubModel", it.hubModel)
                    .toString()
            },
        ).apply()

    fun clear() = sp.edit().remove("refresh").remove("account").apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null if the stored value cannot be read, e.g. after the key was wiped. */
    private fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }.getOrNull()

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "wiser_cloud_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
