package com.jot.app.companion

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class DeviceTokenStore(context: Context, private val prefs: SharedPreferences) {
    private val alias = "${context.packageName}.companion.device_token.v1"

    fun read(): String? = synchronized(lock) {
        try {
            val stored = prefs.getString(ENCRYPTED, null)
            if (stored != null) {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                if (bytes.size !in (IV_BYTES + TAG_BYTES + 1)..(IV_BYTES + TAG_BYTES + MAX_TOKEN)) return@synchronized null
                val key = keyStore().getKey(alias, null) as? SecretKey ?: return@synchronized null
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, bytes, 0, IV_BYTES))
                val plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
                return@synchronized String(plaintext, Charsets.US_ASCII).takeIf(::valid)
            }
            val legacy = prefs.getString(LEGACY, null) ?: return@synchronized null
            legacy.takeIf { write(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun write(token: String): Boolean = synchronized(lock) {
        if (!valid(token)) return@synchronized false
        try {
            val store = keyStore()
            val cipher = Cipher.getInstance(CIPHER)
            try {
                val key = store.getKey(alias, null) as? SecretKey ?: generateKey()
                cipher.init(Cipher.ENCRYPT_MODE, key)
            } catch (_: KeyPermanentlyInvalidatedException) {
                store.deleteEntry(alias)
                cipher.init(Cipher.ENCRYPT_MODE, generateKey())
            } catch (_: UnrecoverableKeyException) {
                store.deleteEntry(alias)
                cipher.init(Cipher.ENCRYPT_MODE, generateKey())
            }
            val iv = cipher.iv
            if (iv.size != IV_BYTES) return@synchronized false
            val encrypted = iv + cipher.doFinal(token.toByteArray(Charsets.US_ASCII))
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            val previous = prefs.getString(ENCRYPTED, null)
            val legacy = prefs.getString(LEGACY, null)
            if (!prefs.edit().putString(ENCRYPTED, encoded).remove(LEGACY).commit()) {
                // SharedPreferences updates its in-memory map even when the disk commit fails.
                prefs.edit().putString(ENCRYPTED, previous).putString(LEGACY, legacy).commit()
                return@synchronized false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear() {
        synchronized(lock) { prefs.edit().remove(ENCRYPTED).remove(LEGACY).commit() }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun valid(token: String) = token.length in 1..MAX_TOKEN && token.all { it.code in 33..126 }

    private companion object {
        val lock = Any()
        const val KEYSTORE = "AndroidKeyStore"
        const val CIPHER = "AES/GCM/NoPadding"
        const val LEGACY = "access_token"
        const val ENCRYPTED = "device_token_v1"
        const val MAX_TOKEN = 512
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
