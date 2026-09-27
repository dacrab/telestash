package com.telestash.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TDLib database encryption key wrapped by Android Keystore; only the
 * Keystore-AES/GCM-wrapped copy is stored in preferences.
 */
object DbKeyStore {
    private const val KS_ALIAS = "telestash_tdlib_key"
    private const val PREFS = "telestash_secure"
    private const val PREF_KEY = "tdlib_key_wrapped"
    private const val TDLIB_KEY_BYTES = 64
    private const val AES_KEY_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    @Suppress("UseKtx")
    @Synchronized
    fun tdlibKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY, null)
        if (stored == null) {
            val fresh = ByteArray(TDLIB_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val wrapped = wrap(fresh)
            check(prefs.edit().putString(PREF_KEY, wrapped).commit()) {
                "Could not persist TDLib database key"
            }
            return fresh
        }
        return try {
            unwrap(stored)
        } catch (e: Exception) {
            throw IllegalStateException("Could not unwrap stored TDLib database key", e)
        }
    }

    private fun keystoreKey(createIfMissing: Boolean = true): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!createIfMissing) error("TDLib Keystore key is missing")

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec
                .Builder(
                    KS_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return kg.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, GCM_IV_BYTES)
        val ct = all.copyOfRange(GCM_IV_BYTES, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(createIfMissing = false),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(ct)
    }
}
