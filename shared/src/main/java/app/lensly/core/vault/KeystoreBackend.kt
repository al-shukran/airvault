package app.lensly.core.vault

import android.content.Context
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.SecretKey

/**
 * The Keystore operations a [Vault] needs. The app uses [AndroidKeystoreBackend]. Unit tests use an in-memory backend,
 * because the Android Keystore is not available on the JVM.
 */
interface KeystoreBackend {
    /** Replaces the hardware wrapping key and returns it. */
    fun createHardwareKey(): SecretKey

    /** The hardware wrapping key. Throws [VaultException] if it has been removed. */
    fun hardwareKey(): SecretKey

    /** Creates the slot key, the HMAC key that binds every slot's software key to this device, replacing any key under that name. */
    fun createSlotKey()

    /** HMAC-SHA256 of [message] under the slot key. Throws [VaultException] if the key has been removed. */
    fun slotMac(message: ByteArray): ByteArray

    /** Creates the header key, the HMAC key that MACs the vault header, replacing any key under that name. */
    fun createHeaderKey()

    /** HMAC-SHA256 of [message] under the header key. Throws [VaultException] if the key has been removed. */
    fun headerMac(message: ByteArray): ByteArray

    /** Creates the Delay State MAC key for [seq], replacing any key under that number, and returns it. */
    fun createFailureMacKey(seq: Long): SecretKey

    /** The Delay State MAC key for [seq], or null once it has been deleted. */
    fun failureMacKey(seq: Long): SecretKey?

    /** Deletes every Delay State MAC key except the one for [keepSeq]. A null [keepSeq] deletes them all. */
    fun deleteFailureMacKeysExcept(keepSeq: Long?)

    /** Deletes the Delay State MAC key for [seq], if there is one. */
    fun deleteFailureMacKey(seq: Long)

    /** The sequence numbers of the Delay State MAC keys the Keystore holds. Throws if the Keystore cannot list them. */
    fun failureMacKeySeqs(): List<Long>

    /**
     * Creates the registry floor key for [seq], replacing any key under that number, and returns it. The registry's seal (see
     * [app.lensly.core.registry.update.RegistrySeal]) is MACed under the key of its floor, and a floor is only ever replaced by a higher
     * one, so a key's number is the highest sequence this device has accepted. Registry keys are not vault keys: [deleteAll] leaves them.
     */
    fun createRegistryMacKey(seq: Long): SecretKey

    /** The registry floor key for [seq], or null once it has been deleted. */
    fun registryMacKey(seq: Long): SecretKey?

    /**
     * Deletes every registry floor key except the one for [keepSeq], lowest first, and stops at the first failure. A failure part-way
     * therefore leaves the highest key, which still verifies the seal of that floor, or only [keepSeq]'s key, which is never
     * deleted. A null [keepSeq] deletes them all.
     */
    fun deleteRegistryMacKeysExcept(keepSeq: Long?)

    /** The sequence numbers of the registry floor keys the Keystore holds. Throws if the Keystore cannot list them. */
    fun registryMacKeySeqs(): List<Long>

    /**
     * Anchors the revocation of the registry signing key [keyId] by making a Keystore key under it, unless one is there. File access
     * cannot delete a Keystore key, so a revocation held here outlives the registry's files. A revocation is permanent: nothing removes
     * these keys, and [deleteAll] leaves them.
     */
    fun anchorRegistryRevocation(keyId: String)

    /** The IDs of the registry signing keys whose revocation is anchored. Throws if the Keystore cannot list them. */
    fun registryRevokedKeyIds(): List<String>

    /** Creates the key of wallet folder [keyId], replacing any key under that id, and returns it. */
    fun createFolderKey(keyId: String): SecretKey

    /** The key of wallet folder [keyId], or null once it has been deleted. */
    fun folderKey(keyId: String): SecretKey?

    /** Deletes the key of wallet folder [keyId], if there is one. */
    fun deleteFolderKey(keyId: String)

    /** The ids of the wallet folder keys the Keystore holds. Throws if the Keystore cannot list them. */
    fun folderKeyIds(): List<String>

    /** Creates the key that wraps the private photo key of photos taken before the first setup, replacing any key there, and returns it. */
    fun createPendingPhotoKey(): SecretKey

    /** The key that wraps the private photo key of photos taken before the first setup, or null once it has been deleted. */
    fun pendingPhotoKey(): SecretKey?

    /** Deletes the key that wraps the private photo key of photos taken before the first setup, if there is one. */
    fun deletePendingPhotoKey()

    /** Removes every key the vault uses on this device. */
    fun deleteAll()

    /** Deletes the key that seals the Vault Key for biometric unlock, if there is one. A backend without one has nothing to delete. */
    fun deleteBiometricKey() {}

    /** The `KeyInfo.securityLevel` that the Keystore reports for [key]. */
    fun securityLevel(key: SecretKey): Int

    /** True when the device has a StrongBox. */
    fun strongBox(): Boolean

    /** The device's Keystore capabilities, probed without a vault. */
    fun probe(): DeviceSecurity
}

/**
 * Runs a Keystore call. A Keystore error becomes a [VaultException], so a caller reports it and does not crash, and it is never
 * taken for a key that does not exist.
 */
internal inline fun <T> keystoreCall(block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw VaultException("The Keystore could not complete a key operation.", e)
} catch (e: ProviderException) {
    throw VaultException("The Keystore could not complete a key operation.", e)
}

/** The app's Keystore, for the callers outside this package that need one, such as the registry store. */
fun androidKeystore(context: Context): KeystoreBackend = AndroidKeystoreBackend(context.applicationContext)

/**
 * The app's backend: the non-exportable keys in the Android Keystore, through [VaultKeystore]. [hardwareKeyUserAuth] is true in the
 * app; only [app.lensly.core.vault.instrumentedVault] passes false.
 */
internal class AndroidKeystoreBackend(
    private val context: Context,
    private val hardwareKeyUserAuth: Boolean = true,
) : KeystoreBackend {
    override fun createHardwareKey(): SecretKey = VaultKeystore.createHardwareKey(userAuth = hardwareKeyUserAuth)

    override fun hardwareKey(): SecretKey = VaultKeystore.hardwareKey()

    override fun createSlotKey() = VaultKeystore.createSlotKey()

    override fun slotMac(message: ByteArray): ByteArray = VaultKeystore.slotMac(message)

    override fun createHeaderKey() = VaultKeystore.createHeaderKey()

    override fun headerMac(message: ByteArray): ByteArray = VaultKeystore.headerMac(message)

    override fun createFailureMacKey(seq: Long): SecretKey = VaultKeystore.createFailureMacKey(seq)

    override fun failureMacKey(seq: Long): SecretKey? = VaultKeystore.failureMacKey(seq)

    override fun deleteFailureMacKeysExcept(keepSeq: Long?) = VaultKeystore.deleteFailureMacKeysExcept(keepSeq)

    override fun deleteFailureMacKey(seq: Long) = VaultKeystore.deleteFailureMacKey(seq)

    override fun failureMacKeySeqs(): List<Long> = VaultKeystore.failureMacKeySeqs()

    override fun createRegistryMacKey(seq: Long): SecretKey = VaultKeystore.createRegistryMacKey(seq)

    override fun registryMacKey(seq: Long): SecretKey? = VaultKeystore.registryMacKey(seq)

    override fun deleteRegistryMacKeysExcept(keepSeq: Long?) = VaultKeystore.deleteRegistryMacKeysExcept(keepSeq)

    override fun registryMacKeySeqs(): List<Long> = VaultKeystore.registryMacKeySeqs()

    override fun anchorRegistryRevocation(keyId: String) = VaultKeystore.anchorRegistryRevocation(keyId)

    override fun registryRevokedKeyIds(): List<String> = VaultKeystore.registryRevokedKeyIds()

    override fun createFolderKey(keyId: String): SecretKey = VaultKeystore.createFolderKey(keyId)

    override fun folderKey(keyId: String): SecretKey? = VaultKeystore.folderKey(keyId)

    override fun deleteFolderKey(keyId: String) = VaultKeystore.deleteFolderKey(keyId)

    override fun folderKeyIds(): List<String> = VaultKeystore.folderKeyIds()

    override fun createPendingPhotoKey(): SecretKey = VaultKeystore.createPendingPhotoKey()

    override fun pendingPhotoKey(): SecretKey? = VaultKeystore.pendingPhotoKey()

    override fun deletePendingPhotoKey() = VaultKeystore.deletePendingPhotoKey()

    override fun deleteAll() = VaultKeystore.deleteAll()

    override fun deleteBiometricKey() = VaultKeystore.deleteBiometricKey()

    override fun securityLevel(key: SecretKey): Int = VaultKeystore.securityLevel(key)

    override fun strongBox(): Boolean = VaultKeystore.strongBoxFeature(context)

    override fun probe(): DeviceSecurity = VaultKeystore.probe(context)
}
