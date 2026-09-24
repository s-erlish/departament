package com.v2ray.ang.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tencent.mmkv.MMKV
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides a stable 16-byte crypt key for encrypting the [AuthTokenStore] MMKV instance.
 *
 * A random 16-char secret is generated once, sealed with an AES/GCM key held in the
 * AndroidKeyStore (non-exportable), and the ciphertext+IV persisted in a tiny plaintext MMKV
 * "key holder" (the sealed secret is useless without the Keystore key). Subsequent runs unseal
 * the same secret.
 *
 * [cryptKey] answers three different questions with three different values, and telling them apart
 * is the whole point. This used to return a nullable string: "the Keystore has never been used
 * here" and "the Keystore just failed to unseal a secret it holds" both came back as null, and the
 * caller opened the *same encrypted file* with no key on both. The second case is the damaging one
 * — the records are still ciphertext, so every read comes back empty (indistinguishable from "no
 * session") and every subsequent write puts plaintext into a file whose other records are not.
 */
object KeystoreKeyProvider {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "departament_auth_aes"
    private const val HOLDER_ID = "departament_keyholder"
    private const val K_IV = "iv"
    private const val K_CIPHER = "cipher"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** What the Keystore can currently say about the [AuthTokenStore] crypt key. */
    sealed interface CryptKeyState {

        /** The secret is in hand: open the store with [key]. */
        data class Available(val key: String) : CryptKeyState

        /**
         * Nothing has ever been sealed on this device and nothing can be (no usable Keystore), so
         * no byte of the store was ever written encrypted. A plaintext store is readable, honest
         * and the only thing left — this is the historic fallback, now reachable only in the one
         * case where it is actually correct.
         */
        object Absent : CryptKeyState

        /**
         * A sealed secret exists but could not be unsealed right now. The store IS encrypted, so
         * it must NOT be opened: not with no key, and not with a fresh one. The caller reports
         * "the session cannot be read at the moment" and retries later — a Keystore that was busy
         * or briefly unavailable heals on the next attempt.
         */
        data class Unsealable(val cause: Throwable) : CryptKeyState
    }

    /**
     * Resolves the MMKV crypt key for [AuthTokenStore], creating and sealing one on first use.
     *
     * Every failure is classified rather than flattened: see [CryptKeyState]. A holder that cannot
     * even be opened counts as [CryptKeyState.Unsealable], not [CryptKeyState.Absent] — we cannot
     * prove the store is plaintext, and guessing wrong here is what corrupts it.
     */
    fun cryptKey(): CryptKeyState {
        val holder = try {
            MMKV.mmkvWithID(HOLDER_ID, MMKV.MULTI_PROCESS_MODE)
        } catch (e: Throwable) {
            return CryptKeyState.Unsealable(e)
        }

        val sealedMaterial = try {
            holder.decodeString(K_IV) to holder.decodeString(K_CIPHER)
        } catch (e: Throwable) {
            return CryptKeyState.Unsealable(e)
        }
        val ivB64 = sealedMaterial.first
        val cipherB64 = sealedMaterial.second

        if (!ivB64.isNullOrBlank() && !cipherB64.isNullOrBlank()) {
            return try {
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val cipher = Base64.decode(cipherB64, Base64.NO_WRAP)
                CryptKeyState.Available(unseal(getOrCreateKey(), iv, cipher))
            } catch (e: Throwable) {
                CryptKeyState.Unsealable(e)
            }
        }

        return try {
            val secret = randomSecret()
            val sealed = seal(getOrCreateKey(), secret)
            holder.encode(K_IV, Base64.encodeToString(sealed.first, Base64.NO_WRAP))
            holder.encode(K_CIPHER, Base64.encodeToString(sealed.second, Base64.NO_WRAP))
            CryptKeyState.Available(secret)
        } catch (e: Throwable) {
            CryptKeyState.Absent
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** @return Pair(iv, ciphertext) */
    private fun seal(key: SecretKey, plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return cipher.iv to ct
    }

    private fun unseal(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** 16-char hex secret == exactly 16 bytes, satisfying MMKV's crypt-key length limit. */
    private fun randomSecret(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
