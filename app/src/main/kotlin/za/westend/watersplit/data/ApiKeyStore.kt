package za.westend.watersplit.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the person's Claude API key on this phone only, encrypted with a key that lives in the Android Keystore
 * (so the file on its own is useless, and a restored backup on another phone simply has no key).
 * The key is never written to the app's data file, never logged and never part of a backup that can be read.
 */
class ApiKeyStore(context: Context) {
    private val file = File(context.filesDir, "claude-key.enc")

    fun hasKey(): Boolean = get() != null

    /** The saved key, or null when none is saved (or it can no longer be decrypted). */
    fun get(): String? {
        if (!file.exists()) return null
        return try {
            val raw = Base64.decode(file.readText().trim(), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun set(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        file.writeText(Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
    }

    fun clear() {
        file.delete()
    }

    /** "sk-ant-api…wxyz": enough to recognise the key without showing it. */
    fun masked(): String? = get()?.let { k -> if (k.length <= 12) "••••" else k.take(7) + "…" + k.takeLast(4) }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "water_split_claude_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
