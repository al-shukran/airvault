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
 * - `k2`: HMAC-SHA256, StrongBox when available, otherwise TEE. The software key of every slot is bound to it, so
 *   each password guess needs this device (see [Vault]).
 * - `k3`: HMAC-SHA256, StrongBox when available, otherwise TEE. It MACs `header.bin`, which is checked before any
 *   password, so a changed header erases the vault (see [VaultHeader]).
 * - `k7.<seq>`: HMAC-SHA256, one for each Delay State write. It authenticates the failure-delay state
 *   with that sequence number. The keys of earlier writes are deleted, so an older state no longer verifies (see [FailureStore]).
 * - `k9.<seq>`: HMAC-SHA256, one for each accepted registry package. It authenticates the registry seal with that floor. Only the
 *   newest is kept, so a seal that was copied back no longer verifies (see [app.lensly.core.registry.update.RegistrySeal]). Not a
 *   vault key: [deleteAll] leaves it, so erasing the vault does not reset the registry's rollback floor.
 * - `k10.<id>`: HMAC-SHA256, one for each registry signing key whose revocation this device accepted, named by the key's ID. The key
 *   is only a marker: file access cannot delete it, so the revocation outlives the registry's files (see
 *   [app.lensly.core.registry.update.RegistryUpdate]). Never deleted, and [deleteAll] leaves it.
 * - `k8.<id>`: AES-256-GCM, one for each wallet folder, wallet or unused slot alike. The id is random and kept in
 *   the folder's key file (see [WalletFolder]). Deleting the key makes that folder's key file unreadable, even with the Vault Key.
 * - `k5`: AES-256-GCM. It wraps the private photo key of photos taken before the first setup
 *   (see [PendingPhotoKey]). The vault takes that key over when it is created, and the key is deleted with it.
 * - `k6`: AES-256-GCM. It seals the Vault Key for Lumio's biometric unlock (see [BiometricUnlock]). Each use needs
 *   a strong biometric, and a new enrollment invalidates the key.
 */
internal object VaultKeystore {
    const val HARDWARE_KEY_ALIAS = "k1"

    /** How long one device authentication lets the hardware key be used. A later use asks for one again. */
    const val HARDWARE_KEY_AUTH_SECONDS = 30

    /** Every Delay State MAC key alias starts with this. */
    private const val FAILURE_MAC_PREFIX = "k7"

    /** Every registry floor key alias starts with this. */
    private const val REGISTRY_MAC_PREFIX = "k9"

    /** Every registry revocation marker alias starts with this. */
    private const val REGISTRY_REVOKED_PREFIX = "k10."

    /** Every wallet folder key alias starts with this. */
    private const val FOLDER_KEY_PREFIX = "k8."

    private const val SLOT_KEY_ALIAS = "k2"
    private const val HEADER_KEY_ALIAS = "k3"
    private const val PENDING_PHOTO_ALIAS = "k5"
    private const val PROBE_ALIAS = "k4"
    private const val BIOMETRIC_KEY_ALIAS = "k6"
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Replaces the hardware wrapping key. Tries StrongBox first and falls back to the TEE. With [userAuth] it needs a screen lock
     * ([isDeviceSecure]). Only [app.lensly.core.vault.instrumentedVault] asks for a key without it.
     */
    fun createHardwareKey(userAuth: Boolean): SecretKey {
        delete(HARDWARE_KEY_ALIAS)
        return generateAes(HARDWARE_KEY_ALIAS) { if (userAuth) requireHardwareUserAuth() }
    }

    /** True when the device has a screen lock (a PIN, pattern or password). The hardware key cannot be made without one. */
    fun isDeviceSecure(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /**
     * Creates the key of wallet folder [keyId], replacing any key under that id. It is TEE-backed and not StrongBox: every folder has
     * a key, and StrongBox holds few keys and is slow. As for the other keys, a key that is not hardware-backed is refused in a
     * release build.
     */
    fun createFolderKey(keyId: String): SecretKey = createAesKey(folderAlias(keyId), "wallet folder key")

    /** The key of wallet folder [keyId], or null once it has been deleted. */
    fun folderKey(keyId: String): SecretKey? = get(folderAlias(keyId))

    /** Deletes the key of wallet folder [keyId], if the Keystore holds one. */
    fun deleteFolderKey(keyId: String) = delete(folderAlias(keyId))

    /** The ids of the wallet folder keys the Keystore holds. Throws a GeneralSecurityException if it cannot list them. */
    fun folderKeyIds(): List<String> = Collections.list(keyStore().aliases())
        .filter { it.startsWith(FOLDER_KEY_PREFIX) }
        .map { it.removePrefix(FOLDER_KEY_PREFIX) }

    /** Creates the key that wraps the private photo key of photos taken before the first setup, replacing any key there. */
    fun createPendingPhotoKey(): SecretKey = createAesKey(PENDING_PHOTO_ALIAS, "pending photo key")

    /** The key that wraps the private photo key of photos taken before the first setup, or null once it has been deleted. */
    fun pendingPhotoKey(): SecretKey? = get(PENDING_PHOTO_ALIAS)

    /** Deletes the key that wraps the private photo key of photos taken before the first setup, if the Keystore holds one. */
    fun deletePendingPhotoKey() = delete(PENDING_PHOTO_ALIAS)

    /** The existing hardware wrapping key. Throws [VaultKeyInvalidatedException] if it has been removed, which no vault survives. */
    fun hardwareKey(): SecretKey =
        get(HARDWARE_KEY_ALIAS) ?: throw VaultKeyInvalidatedException("The vault's hardware key is missing from the Keystore.")

    /**
     * Creates the key that seals the Vault Key for biometric unlock, replacing any key there. Each use needs a strong biometric, and a new
     * enrollment invalidates the key. It is TEE-backed, like the other keys that are not the hardware key.
     */
    fun createBiometricKey(): SecretKey = createAesKey(BIOMETRIC_KEY_ALIAS, "biometric unlock key") {
        setUserAuthenticationRequired(true)
        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        setInvalidatedByBiometricEnrollment(true)
    }

    /** The key that seals the Vault Key for biometric unlock. Throws if it has been removed. */
    fun biometricKey(): SecretKey =
        get(BIOMETRIC_KEY_ALIAS) ?: throw VaultException("The biometric unlock key is missing from the Keystore.")

    /** Deletes the biometric unlock key, if the Keystore holds one. */
    fun deleteBiometricKey() = delete(BIOMETRIC_KEY_ALIAS)

    /**
     * Creates the Delay State MAC key for [seq], replacing any key under that number. It is StrongBox when the device has one,
     * and the TEE otherwise. As for the vault key, a key that is not hardware-backed is refused in a release build.
     */
    fun createFailureMacKey(seq: Long): SecretKey = createHmacKey(failureMacAlias(seq), "Delay State MAC key")

    /** Creates the registry floor key for [seq], replacing any key under that number. Hardware-backed, as the Delay State MAC key is. */
    fun createRegistryMacKey(seq: Long): SecretKey = createHmacKey(registryMacAlias(seq), "registry floor key")

    /** The registry floor key for [seq], or null once it has been deleted. */
    fun registryMacKey(seq: Long): SecretKey? = get(registryMacAlias(seq))

    /**
     * Deletes every registry floor key except the one for [keepSeq], lowest first, so a failure part-way leaves the highest key and
     * the seal it verifies. With a [keepSeq], that key is never deleted, so a failure cannot leave the Keystore with none. A null
     * [keepSeq] deletes them all.
     */
    fun deleteRegistryMacKeysExcept(keepSeq: Long?) {
        val store = keyStore()
        registryAliasesToDelete(Collections.list(store.aliases()), keepSeq).forEach { store.deleteEntry(it) }
    }

    /**
     * The registry floor key aliases among [aliases] to delete, all but the one for [keepSeq], in the order to delete them: lowest
     * sequence first, so a delete that stops part-way leaves the highest key. An alias with no sequence number comes first.
     */
    internal fun registryAliasesToDelete(aliases: List<String>, keepSeq: Long?): List<String> {
        val keep = keepSeq?.let { registryMacAlias(it) }
        return aliases
            .filter { it.startsWith("$REGISTRY_MAC_PREFIX.") && it != keep }
            .sortedBy { it.removePrefix("$REGISTRY_MAC_PREFIX.").toLongOrNull() ?: Long.MIN_VALUE }
    }

    /** The sequence numbers of the registry floor keys the Keystore holds. Throws a GeneralSecurityException if it cannot list them. */
    fun registryMacKeySeqs(): List<Long> = Collections.list(keyStore().aliases())
        .filter { it.startsWith("$REGISTRY_MAC_PREFIX.") }
        .mapNotNull { it.removePrefix("$REGISTRY_MAC_PREFIX.").toLongOrNull() }

    /** Makes the marker key of a revoked registry signing key, unless there is one. Hardware-backed, as the registry floor key is. */
    fun anchorRegistryRevocation(keyId: String) {
        val alias = REGISTRY_REVOKED_PREFIX + keyId
        if (!keyStore().containsAlias(alias)) createHmacKey(alias, "registry revocation key")
    }

    /** The IDs of the registry signing keys with a marker key. Throws a GeneralSecurityException if the Keystore cannot list them. */
    fun registryRevokedKeyIds(): List<String> = Collections.list(keyStore().aliases())
        .filter { it.startsWith(REGISTRY_REVOKED_PREFIX) }
        .map { it.removePrefix(REGISTRY_REVOKED_PREFIX) }

    /**
     * Creates the slot key, the HMAC key that binds every slot's software key to this device, replacing any key under that name. It is
     * StrongBox when the device has one, and the TEE otherwise. A key that is not hardware-backed is refused in a release build.
     */
    fun createSlotKey() {
        createHmacKey(SLOT_KEY_ALIAS, "vault slot key")
    }

    /**
     * Creates the header key, the HMAC key that MACs the vault header, replacing any key under that name. It is StrongBox when the device
     * has one, and the TEE otherwise. As for the slot key, a key that is not hardware-backed is refused in a release build.
     */
    fun createHeaderKey() {
        createHmacKey(HEADER_KEY_ALIAS, "vault header key")
    }

    /**
     * HMAC-SHA256 of [message] under the slot key. Throws [VaultKeyInvalidatedException] if the key has been removed, which no vault
     * survives, or [VaultException] if the Keystore refuses the operation.
     */
    fun slotMac(message: ByteArray): ByteArray = macUnder(SLOT_KEY_ALIAS, "slot", message)

    /** HMAC-SHA256 of [message] under the header key. Throws if the key has been removed, or if the Keystore refuses the operation. */
    fun headerMac(message: ByteArray): ByteArray = macUnder(HEADER_KEY_ALIAS, "header", message)

    private fun macUnder(alias: String, what: String, message: ByteArray): ByteArray {
        val key = get(alias) ?: throw VaultKeyInvalidatedException("The vault's $what key is missing from the Keystore.")
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

    /**
     * Creates an AES key under [alias], replacing any key there. It is TEE-backed and not StrongBox: StrongBox holds few keys and is slow.
     * A key that is not hardware-backed is refused in a release build, and [what] names it in that error.
     */
    private fun createAesKey(alias: String, what: String, auth: KeyGenParameterSpec.Builder.() -> Unit = {}): SecretKey {
        delete(alias)
        val key = generateAes(alias, strongBox = false, auth = auth)
        val verdict = KeystorePolicy.verdict(isHardwareBacked(securityLevel(key)), BuildConfig.DEBUG, deviceIsEmulator())
        if (verdict == KeystoreVerdict.Refused) {
            delete(alias)
            throw GeneralSecurityException("The $what is not hardware-backed.")
        }
        return key
    }

    /**
     * True for every alias the vault uses: the hardware key, the slot key, the probe, the pending photo key, the biometric key, and
     * every other folder or MAC key.
     */
    internal fun isVaultAlias(alias: String): Boolean =
        alias == HARDWARE_KEY_ALIAS || alias == SLOT_KEY_ALIAS || alias == HEADER_KEY_ALIAS || alias == PROBE_ALIAS ||
            alias == PENDING_PHOTO_ALIAS || alias == BIOMETRIC_KEY_ALIAS || alias.startsWith(FAILURE_MAC_PREFIX) ||
            alias.startsWith(FOLDER_KEY_PREFIX)

    private fun failureMacAlias(seq: Long): String = "$FAILURE_MAC_PREFIX.$seq"

    private fun registryMacAlias(seq: Long): String = "$REGISTRY_MAC_PREFIX.$seq"

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

    /** An AES key, in StrongBox when the device has one and in the TEE otherwise. [auth] sets the key's user-auth rules. */
    private fun generateAes(alias: String, auth: KeyGenParameterSpec.Builder.() -> Unit = {}): SecretKey = try {
        generateAes(alias, strongBox = true, auth = auth)
    } catch (e: StrongBoxUnavailableException) {
        generateAes(alias, strongBox = false, auth = auth)
    } catch (e: ProviderException) {
        if (e.cause is StrongBoxUnavailableException) generateAes(alias, strongBox = false, auth = auth) else throw e
    }

    private fun generateAes(alias: String, strongBox: Boolean, auth: KeyGenParameterSpec.Builder.() -> Unit): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox)
            .apply(auth)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    /**
     * The hardware key's user auth: each use needs a device authentication within [HARDWARE_KEY_AUTH_SECONDS]. The key is not
     * invalidated by a new fingerprint, so only removing or changing the screen lock costs the vault its key.
     */
    private fun KeyGenParameterSpec.Builder.requireHardwareUserAuth() {
        setUserAuthenticationRequired(true)
        setUserAuthenticationParameters(
            HARDWARE_KEY_AUTH_SECONDS,
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
        )
        setInvalidatedByBiometricEnrollment(false)
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
