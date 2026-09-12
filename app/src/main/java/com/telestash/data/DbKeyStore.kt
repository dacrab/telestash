package com.telestash.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher

/**
 * Generates and stores the TDLib database encryption key wrapped by Android Keystore.
 * The plaintext key never leaves the keystore boundary unencrypted at rest: we store
 * only a Keystore-wrapped (AES/GCM) copy in app preferences.
 */
object DbKeyStore {

    private const val KS_ALIAS = "telestash_tdlib_key"
    private const val PREFS = "telestash_secure"
    private const val PREF_KEY = "tdlib_key_wrapped"

    /**
     * Returns the raw TDLib database encryption key (64 random bytes).
     * First call generates it; subsequent calls decrypt the stored copy with the
     * Keystore key.
     */
    @Synchronized
    fun tdlibKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY, null)

        val key: ByteArray = if (stored != null) {
            unwrap(stored)
        } else {
            SecureRandom().let { r ->
                ByteArray(64).also { r.nextBytes(it) }
            }.also { fresh ->
                prefs.edit().putString(PREF_KEY, wrap(fresh)).apply()
            }
        }
        return key
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
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
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
