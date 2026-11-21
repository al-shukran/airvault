package app.lensly.core.vault

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.lensly.shared.BuildConfig
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Collections
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Keystore keys used by the vault. All of them are non-exportable.
 *
 * - [HARDWARE_KEY_ALIAS]: AES-256-GCM. It is the hardware layer that wraps the vault key. It is StrongBox
 *   when available, otherwise TEE. Its use needs the screen lock or a strong biometric within
 *   [HARDWARE_KEY_AUTH_SECONDS] of the last authentication. Removing or changing the screen lock invalidates
 *   it for good, so the vault must then be restored from its seed phrase.
 * - `lensly.vault.slot-mac`: HMAC-SHA256, StrongBox when available, otherwise TEE. The software key of every slot is bound to it, so
 *   each password guess needs this device (see [Vault]).
 * - `lensly.vault.failure-mac.<seq>`: HMAC-SHA256, one for each Delay State write. It authenticates the failure-delay state
 *   with that sequence number. The keys of earlier writes are deleted, so an older state no longer verifies (see [FailureStore]).
 * - `lensly.vault.folder.<id>`: AES-256-GCM, one for each wallet folder, wallet or unused slot alike. The id is random and kept in
 *   the folder's key file (see [WalletFolder]). Deleting the key makes that folder's key file unreadable, even with the Vault Key.
 */
internal object VaultKeystore {
    const val HARDWARE_KEY_ALIAS = "lensly.vault.hw"

    /** How long one device authentication lets the hardware key be used. A later use asks for one again. */
    const val HARDWARE_KEY_AUTH_SECONDS = 30

    /** Every Delay State MAC key alias starts with this. */
    private const val FAILURE_MAC_PREFIX = "lensly.vault.failure-mac"

    /** Every wallet folder key alias starts with this. */
    private const val FOLDER_KEY_PREFIX = "lensly.vault.folder."

    private const val SLOT_KEY_ALIAS = "lensly.vault.slot-mac"
    private const val PROBE_ALIAS = "lensly.vault.probe"
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Replaces the hardware wrapping key. Tries StrongBox first and falls back to the TEE. With [userAuth] it needs a screen lock
     * ([isDeviceSecure]). Only [app.lensly.core.vault.instrumentedVault] asks for a key without it.
     */
    fun createHardwareKey(userAuth: Boolean): SecretKey {
        delete(HARDWARE_KEY_ALIAS)
        return generateAes(HARDWARE_KEY_ALIAS, userAuth = userAuth)
    }

    /** True when the device has a screen lock (a PIN, pattern or password). The hardware key cannot be made without one. */
    fun isDeviceSecure(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /**
     * Creates the key of wallet folder [keyId], replacing any key under that id. It is TEE-backed and not StrongBox: every folder has
     * a key, and StrongBox holds few keys and is slow. As for the other keys, a key that is not hardware-backed is refused in a
     * release build.
     */
    fun createFolderKey(keyId: String): SecretKey {
        val alias = folderAlias(keyId)
        delete(alias)
        val key = generateAes(alias, strongBox = false)
        val verdict = KeystorePolicy.verdict(isHardwareBacked(securityLevel(key)), BuildConfig.DEBUG, deviceIsEmulator())
        if (verdict == KeystoreVerdict.Refused) {
            delete(alias)
            throw GeneralSecurityException("The wallet folder key is not hardware-backed.")
        }
        return key
    }

    /** The key of wallet folder [keyId], or null once it has been deleted. */
    fun folderKey(keyId: String): SecretKey? = get(folderAlias(keyId))

    /** Deletes the key of wallet folder [keyId], if the Keystore holds one. */
    fun deleteFolderKey(keyId: String) = delete(folderAlias(keyId))

    /** The ids of the wallet folder keys the Keystore holds. Throws a GeneralSecurityException if it cannot list them. */
    fun folderKeyIds(): List<String> = Collections.list(keyStore().aliases())
        .filter { it.startsWith(FOLDER_KEY_PREFIX) }
        .map { it.removePrefix(FOLDER_KEY_PREFIX) }

    /** The existing hardware wrapping key. Throws if it has been removed. */
    fun hardwareKey(): SecretKey =
        get(HARDWARE_KEY_ALIAS) ?: throw VaultException("The vault's hardware key is missing from the Keystore.")

    /**
     * Creates the Delay State MAC key for [seq], replacing any key under that number. It is StrongBox when the device has one,
     * and the TEE otherwise. As for the vault key, a key that is not hardware-backed is refused in a release build.
     */
    fun createFailureMacKey(seq: Long): SecretKey = createHmacKey(failureMacAlias(seq), "Delay State MAC key")

    /**
     * Creates the slot key, the HMAC key that binds every slot's software key to this device, replacing any key under that name. It is
     * StrongBox when the device has one, and the TEE otherwise. A key that is not hardware-backed is refused in a release build.
     */
    fun createSlotKey() {
        createHmacKey(SLOT_KEY_ALIAS, "vault slot key")
    }

    /** HMAC-SHA256 of [message] under the slot key. Throws if the key has been removed, or if the Keystore refuses the operation. */
    fun slotMac(message: ByteArray): ByteArray {
        val key = get(SLOT_KEY_ALIAS) ?: throw VaultException("The vault's slot key is missing from the Keystore.")
        return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(message)
    }

    /** The Delay State MAC key for [seq], or null once it has been deleted. */
    fun failureMacKey(seq: Long): SecretKey? = get(failureMacAlias(seq))

    /** Deletes every Delay State MAC key except the one for [keepSeq]. A null [keepSeq] deletes them all. */
    fun deleteFailureMacKeysExcept(keepSeq: Long?) {
        val store = keyStore()
        val keep = keepSeq?.let { failureMacAlias(it) }
        Collections.list(store.aliases())
            .filter { it.startsWith(FAILURE_MAC_PREFIX) && it != keep }
            .forEach { store.deleteEntry(it) }
    }

    /** Deletes the Delay State MAC key for [seq], if the Keystore holds one. */
    fun deleteFailureMacKey(seq: Long) = delete(failureMacAlias(seq))

    /** The sequence numbers of the Delay State MAC keys the Keystore holds. Throws a GeneralSecurityException if it cannot list them. */
    fun failureMacKeySeqs(): List<Long> = Collections.list(keyStore().aliases())
        .filter { it.startsWith("$FAILURE_MAC_PREFIX.") }
        .mapNotNull { it.removePrefix("$FAILURE_MAC_PREFIX.").toLongOrNull() }

    /**
     * Removes every key the vault uses on this device. Each one is tried even after another is refused, and the first refusal is rethrown
     * at the end, so the erase still sees that it failed. A Keystore error becomes a [VaultException].
     */
    fun deleteAll() {
        keystoreCall {
            val store = keyStore()
            val vaultAliases = Collections.list(store.aliases()).filter { isVaultAlias(it) }
            forEachKeepingFirstFailure(vaultAliases) { store.deleteEntry(it) }
        }
    }

    /** The `KeyInfo.securityLevel` that the Keystore reports for [key]. */
    fun securityLevel(key: SecretKey): Int {
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return info.securityLevel
    }

    fun isHardwareBacked(securityLevel: Int): Boolean =
        securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
            securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX

    fun strongBoxFeature(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** Reports the device's keystore capabilities by creating and removing a temporary key. */
    fun probe(context: Context): DeviceSecurity {
        val strongBox = strongBoxFeature(context)
        return try {
            delete(PROBE_ALIAS)
            val key = generateAes(PROBE_ALIAS)
            DeviceSecurity(strongBox = strongBox, hardwareBacked = isHardwareBacked(securityLevel(key)))
        } catch (e: GeneralSecurityException) {
            DeviceSecurity(strongBox = strongBox, hardwareBacked = false)
        } catch (e: ProviderException) {
            DeviceSecurity(strongBox = strongBox, hardwareBacked = false)
        } finally {
            delete(PROBE_ALIAS)
        }
    }

    /** True for every alias the vault uses: the hardware key, the slot key, the probe, and every Delay State and wallet folder key. */
    private fun isVaultAlias(alias: String): Boolean =
        alias == HARDWARE_KEY_ALIAS || alias == SLOT_KEY_ALIAS || alias == PROBE_ALIAS ||
            alias.startsWith(FAILURE_MAC_PREFIX) || alias.startsWith(FOLDER_KEY_PREFIX)

    private fun failureMacAlias(seq: Long): String = "$FAILURE_MAC_PREFIX.$seq"

    private fun folderAlias(keyId: String): String = FOLDER_KEY_PREFIX + keyId

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply {
        try {
            load(null)
        } catch (e: IOException) {
            // Reported as a Keystore error, like the others, so no caller sees a bare IOException.
            throw KeyStoreException("The Android Keystore could not be opened.", e)
        }
    }

    private fun get(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun delete(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun generateAes(alias: String, userAuth: Boolean = false): SecretKey = try {
        generateAes(alias, strongBox = true, userAuth = userAuth)
    } catch (e: StrongBoxUnavailableException) {
        generateAes(alias, strongBox = false, userAuth = userAuth)
    } catch (e: ProviderException) {
        if (e.cause is StrongBoxUnavailableException) generateAes(alias, strongBox = false, userAuth = userAuth) else throw e
    }

    /**
     * An AES key. With [userAuth], each use needs a device authentication within [HARDWARE_KEY_AUTH_SECONDS]. The key is not
     * invalidated by a new fingerprint, so only removing or changing the screen lock costs the vault its key.
     */
    private fun generateAes(alias: String, strongBox: Boolean, userAuth: Boolean = false): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (userAuth) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(
                        HARDWARE_KEY_AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    /**
     * Creates an HMAC key under [alias], replacing any key there. It is StrongBox when the device has one, and the TEE otherwise. A key
     * that is not hardware-backed is refused in a release build, and [what] names it in that error.
     */
    private fun createHmacKey(alias: String, what: String): SecretKey {
        delete(alias)
        val key = try {
            generateHmac(alias, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generateHmac(alias, strongBox = false)
        } catch (e: ProviderException) {
            if (e.cause is StrongBoxUnavailableException) generateHmac(alias, strongBox = false) else throw e
        }
        if (KeystorePolicy.verdict(isHardwareBacked(securityLevel(key)), BuildConfig.DEBUG, deviceIsEmulator()) == KeystoreVerdict.Refused) {
            delete(alias)
            throw GeneralSecurityException("The $what is not hardware-backed.")
        }
        return key
    }

    private fun generateHmac(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }
}

/**
 * Runs [action] on every item, even after one of them throws. The first exception is rethrown at the end, and later ones are added to it
 * as suppressed.
 */
internal fun <T> forEachKeepingFirstFailure(items: Iterable<T>, action: (T) -> Unit) {
    var first: Exception? = null
    for (item in items) {
        try {
            action(item)
        } catch (e: Exception) {
            val earlier = first
            if (earlier == null) first = e else earlier.addSuppressed(e)
        }
    }
    first?.let { throw it }
}
