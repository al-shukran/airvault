package app.lensly.core.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM helpers. A sealed blob is `iv (12 bytes) || ciphertext || tag (16 bytes)`.
 *
 * The `open` functions throw [javax.crypto.AEADBadTagException] when the key, the AAD or the blob is wrong.
 */
internal object Aead {
    const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    /** Seals [plaintext] under a software [key] with a fresh random IV. */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    /** Seals under a non-exportable Keystore [key]. The Keystore picks the IV, which is read back from the cipher. */
    fun sealWithKeystore(key: SecretKey, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        return iv + cipher.doFinal(plaintext)
    }

    fun openWithKeystore(key: SecretKey, aad: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
}
