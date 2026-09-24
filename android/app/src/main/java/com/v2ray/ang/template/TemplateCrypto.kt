package com.v2ray.ang.template

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM encryption for hidden ("locked") config templates, backed by a
 * non-exportable key held in the Android Keystore (`AndroidKeyStore`).
 *
 * Threat model (honest): this is on-device concealment, NOT DRM. The plaintext
 * config must exist in memory at connect time, and a user with root, a debugger,
 * or a Keystore-authorised process can still recover it. The guarantee is only:
 *  - the value at rest in MMKV is ciphertext, not readable by a casual storage dump;
 *  - the app never surfaces the plaintext through any share/export/UI path.
 *
 * The key never leaves the Keystore; encrypt/decrypt happen in-process. If the
 * Keystore is unavailable (rare OEM breakage), callers fall back to a clearly
 * labelled base64 obfuscation instead — see [TemplateManager.wrapRawForStorage].
 */
internal object TemplateCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "departament_template_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_LEN = 12

    /**
     * Returns the persistent Keystore AES key, creating it on first use.
     * @return the secret key, or null if the Keystore is unavailable.
     */
    private fun getOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Template Keystore key unavailable", e)
            null
        }
    }

    /**
     * Encrypts [plain] and returns base64(iv || ciphertext+tag), or null on failure.
     */
    fun encrypt(plain: String): String? {
        return try {
            val key = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Template encrypt failed", e)
            null
        }
    }

    /**
     * Decrypts a payload produced by [encrypt]. Returns null on any failure.
     */
    fun decrypt(payload: String): String? {
        return try {
            val key = getOrCreateKey() ?: return null
            val combined = Base64.decode(payload, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LEN) return null
            val iv = combined.copyOfRange(0, GCM_IV_LEN)
            val cipherText = combined.copyOfRange(GCM_IV_LEN, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Template decrypt failed", e)
            null
        }
    }
}
