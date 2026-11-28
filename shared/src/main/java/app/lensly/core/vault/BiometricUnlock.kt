package app.lensly.core.vault

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** What [BiometricUnlock.unlock] found. */
sealed interface BiometricOutcome {
    /** The owner passed the check, and the real vault opened in [session]. The caller keeps the session or closes it. */
    class Unlocked(val session: VaultSession) : BiometricOutcome

    /** The owner did not pass the check, or cancelled it. Nothing changed, and the password is still the way in. */
    data object Declined : BiometricOutcome

    /** Biometric unlock failed, so it is now off and its key and sealed Vault Key are deleted. The password is the way in. */
    data object TurnedOff : BiometricOutcome
}

/** The Vault Key sealed under the biometric key: the IV, then the ciphertext with its GCM tag. */
class WrappedKey(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * The Keystore and prompt steps of [BiometricUnlock]. The app uses [AndroidBiometricCrypto]. Unit tests use a fake, because the Android
 * Keystore is not on the JVM.
 */
interface BiometricCrypto {
    /** True when the device has a strong biometric enrolled, so the key can be used. */
    fun available(): Boolean

    /** Replaces the biometric key. False when the Keystore refuses a key that is not hardware-backed. */
    fun createKey(): Boolean

    /** Asks for the owner's biometric, then wraps [plain] under the key. Null when the owner does not pass. Throws if the key is missing. */
    suspend fun wrap(plain: ByteArray): WrappedKey?

    /**
     * Asks for the owner's biometric, then unwraps [wrapped]. Null when the owner does not pass. Throws if the key is missing or invalidated.
     */
    suspend fun unwrap(wrapped: WrappedKey): ByteArray?

    /** Deletes the biometric key, if there is one. */
    fun deleteKey()
}

/**
 * Lumio's opt-in biometric unlock. The real Vault Key is sealed under a Keystore key that needs a strong biometric for every use, and only
 * the IV and the ciphertext are kept, in [BLOB_FILE_NAME] in the vault's folder. That file exists exactly while biometric unlock is on: it
 * is written only after the owner's biometric has sealed the key, and it is deleted when the setting goes off, when the key is
 * invalidated, or when the vault is wiped. The password always works, and it is never replaced.
 *
 * The Vault Key is the secret the password opens. It is copied only to seal it and to unlock, and each copy is zeroed after use. A Duress
 * Password unlock promotes the Decoy Vault and removes the sealed key with the old vault's other files, so biometric unlock is then off.
 */
class BiometricUnlock internal constructor(
    private val file: File,
    private val crypto: BiometricCrypto,
) {
    constructor(context: Context) : this(
        File(VaultLayout.of(context).root, BLOB_FILE_NAME),
        AndroidBiometricCrypto(context.applicationContext),
    )

    /** True while biometric unlock is on for the real vault. */
    fun isOn(): Boolean = file.isFile

    /** True when the device has a strong biometric enrolled, so biometric unlock can be turned on. */
    fun canEnable(): Boolean = crypto.available()

    /**
     * Turns biometric unlock on for the real vault that [session] has open. The owner's biometric seals the Vault Key first, and the
     * setting is on only once that has worked. What is sealed is the Vault Key and the session's side (see [VaultSession.side]). Returns
     * false, and leaves it off, when the device has no strong biometric or the owner does not pass.
     */
    suspend fun enable(session: VaultSession): Boolean {
        if (!crypto.available()) return false
        if (isOn()) return true
        var plain: ByteArray? = null
        try {
            // Built inside the lock from the session's own key, so no separate copy of the Vault Key exists to be left behind.
            plain = session.withKeyLocked { key -> key.copyOf(key.size + 1).also { it[key.size] = session.side.toByte() } }
            if (!withContext(Dispatchers.IO) { crypto.createKey() }) return false
            val wrapped = crypto.wrap(plain) ?: run {
                discardKey()
                return false
            }
            withContext(Dispatchers.IO) { writeAtomically(file, wrapped.iv + wrapped.ciphertext) }
            return true
        } catch (e: CancellationException) {
            discardKey()
            throw e
        } catch (e: Exception) {
            discardKey()
            return false
        } finally {
            plain?.fill(0)
        }
    }

    /**
     * Turns biometric unlock off: the sealed Vault Key and the biometric key are deleted.
     */
    suspend fun disable() {
        deleteSealedKey()
    }

    private suspend fun deleteSealedKey() = withContext(NonCancellable + Dispatchers.IO) {
        file.delete()
        crypto.deleteKey()
    }

    /**
     * Opens the real vault with the owner's biometric, when biometric unlock is on. A cancel is [BiometricOutcome.Declined]. A failure,
     * such as a key that Android invalidated after a new enrollment, or a sealed key that does not name this vault, turns biometric unlock
     * off and is [BiometricOutcome.TurnedOff].
     */
    suspend fun unlock(vault: Vault): BiometricOutcome {
        if (!isOn()) return BiometricOutcome.Declined
        val plain = try {
            crypto.unwrap(readWrapped()) ?: return BiometricOutcome.Declined
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deleteSealedKey()
            return BiometricOutcome.TurnedOff
        }
        // The Vault Key, then its side. A sealed value of another shape is not this vault's, and turns biometric unlock off.
        val key = plain.copyOf(minOf(plain.size, KEY_BYTES))
        val side = plain.getOrNull(KEY_BYTES)?.toInt()
        plain.fill(0)
        try {
            val session = try {
                if (key.size != KEY_BYTES || (side != 0 && side != 1)) null else vault.unlockWithVaultKey(key, side)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (session == null) {
                deleteSealedKey()
                return BiometricOutcome.TurnedOff
            }
            return BiometricOutcome.Unlocked(session)
        } finally {
            key.fill(0)
        }
    }

    private suspend fun readWrapped(): WrappedKey = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        require(bytes.size > IV_BYTES) { "The sealed Vault Key is damaged." }
        WrappedKey(bytes.copyOf(IV_BYTES), bytes.copyOfRange(IV_BYTES, bytes.size))
    }

    /** Deletes the biometric key after a seal that did not finish, so nothing is left that a later unlock could use. */
    private suspend fun discardKey() = withContext(NonCancellable + Dispatchers.IO) { crypto.deleteKey() }

    companion object {
        /** The sealed Vault Key, in the vault's folder: the IV, then the ciphertext. */
        const val BLOB_FILE_NAME = "aux.bin"

        /** The GCM IV that the Keystore cipher generates. */
        private const val IV_BYTES = 12

        private const val KEY_BYTES = VaultKeyFile.KEY_BYTES
    }
}

/**
 * The app's [BiometricCrypto]: the biometric key in the Android Keystore (see [VaultKeystore]), and the system biometric prompt. The prompt
 * unlocks the key's cipher for one use, and only a strong biometric can do that.
 */
internal class AndroidBiometricCrypto(private val context: Context) : BiometricCrypto {
    override fun available(): Boolean =
        context.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun createKey(): Boolean = try {
        VaultKeystore.createBiometricKey()
        true
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: ProviderException) {
        false
    }

    override suspend fun wrap(plain: ByteArray): WrappedKey? {
        val cipher = withContext(Dispatchers.IO) {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, VaultKeystore.biometricKey()) }
        }
        val authorized = authorize(cipher) ?: return null
        return WrappedKey(authorized.iv, authorized.doFinal(plain))
    }

    override suspend fun unwrap(wrapped: WrappedKey): ByteArray? {
        val cipher = withContext(Dispatchers.IO) {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, VaultKeystore.biometricKey(), GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
            }
        }
        val authorized = authorize(cipher) ?: return null
        return authorized.doFinal(wrapped.ciphertext)
    }

    override fun deleteKey() {
        // A key the Keystore cannot delete is left. Without its sealed Vault Key it opens nothing, and the next enable replaces it.
        try {
            VaultKeystore.deleteBiometricKey()
        } catch (e: GeneralSecurityException) {
            // See above.
        } catch (e: ProviderException) {
            // See above.
        }
    }

    /**
     * Shows the system biometric prompt for [cipher]. Returns the cipher that the owner's biometric unlocked, or null when the owner does
     * not pass or cancels. Only a strong biometric is accepted, because the key needs one.
     */
    @SuppressLint("MissingPermission") // USE_BIOMETRIC is declared by the hosting apps, as for confirmDeviceCredential.
    private suspend fun authorize(cipher: Cipher): Cipher? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Cipher?> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val prompt = BiometricPrompt.Builder(context)
                .setTitle("Unlock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel", context.mainExecutor) { _, _ -> if (continuation.isActive) continuation.resume(null, null) }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                signal,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher, null)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(null, null)
                    }
                },
            )
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
