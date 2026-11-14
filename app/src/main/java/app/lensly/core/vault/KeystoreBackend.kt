package app.lensly.core.vault

import android.content.Context
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.SecretKey

/**
 * The Keystore operations a [Vault] needs. The app uses [AndroidKeystoreBackend]. Unit tests use an in-memory backend,
 * because the Android Keystore is not available on the JVM.
 */
internal interface KeystoreBackend {
    /** Replaces the hardware wrapping key and returns it. */
    fun createHardwareKey(): SecretKey

    /** The hardware wrapping key. Throws [VaultException] if it has been removed. */
    fun hardwareKey(): SecretKey

    /** Creates the Delay State MAC key for [seq], replacing any key under that number, and returns it. */
    fun createFailureMacKey(seq: Long): SecretKey

    /** The Delay State MAC key for [seq], or null once it has been deleted. */
    fun failureMacKey(seq: Long): SecretKey?

    /** The single Delay State MAC key of the layout before per-save keys, or null once it has been deleted. */
    fun legacyFailureMacKey(): SecretKey?

    /** Deletes every Delay State MAC key except the one for [keepSeq], and the legacy key too. A null [keepSeq] deletes them all. */
    fun deleteFailureMacKeysExcept(keepSeq: Long?)

    /** Deletes the Delay State MAC key for [seq], if there is one. */
    fun deleteFailureMacKey(seq: Long)

    /** The sequence numbers of the Delay State MAC keys the Keystore holds. Throws if the Keystore cannot list them. */
    fun failureMacKeySeqs(): List<Long>

    /** Creates the key of wallet folder [keyId], replacing any key under that id, and returns it. */
    fun createFolderKey(keyId: String): SecretKey

    /** The key of wallet folder [keyId], or null once it has been deleted. */
    fun folderKey(keyId: String): SecretKey?

    /** Deletes the key of wallet folder [keyId], if there is one. */
    fun deleteFolderKey(keyId: String)

    /** The ids of the wallet folder keys the Keystore holds. Throws if the Keystore cannot list them. */
    fun folderKeyIds(): List<String>

    /** Removes every key the vault uses on this device. */
    fun deleteAll()

    /** The `KeyInfo.securityLevel` that the Keystore reports for [key]. */
    fun securityLevel(key: SecretKey): Int

    /** True when the device has a StrongBox. */
    fun strongBox(): Boolean

    /** The device's Keystore capabilities, probed without a vault. */
    fun probe(): DeviceSecurity
}

/**
 * Runs a Keystore call on a wallet folder's path. A Keystore error becomes a [VaultException], so a caller reports it and does
 * not crash, and it is never taken for a folder that holds no key.
 */
internal inline fun <T> keystoreCall(block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw VaultException("The Keystore could not complete a wallet folder key operation.", e)
} catch (e: ProviderException) {
    throw VaultException("The Keystore could not complete a wallet folder key operation.", e)
}

/** The app's backend: the non-exportable keys in the Android Keystore, through [VaultKeystore]. */
internal class AndroidKeystoreBackend(private val context: Context) : KeystoreBackend {
    override fun createHardwareKey(): SecretKey = VaultKeystore.createHardwareKey()

    override fun hardwareKey(): SecretKey = VaultKeystore.hardwareKey()

    override fun createFailureMacKey(seq: Long): SecretKey = VaultKeystore.createFailureMacKey(seq)

    override fun failureMacKey(seq: Long): SecretKey? = VaultKeystore.failureMacKey(seq)

    override fun legacyFailureMacKey(): SecretKey? = VaultKeystore.legacyFailureMacKey()

    override fun deleteFailureMacKeysExcept(keepSeq: Long?) = VaultKeystore.deleteFailureMacKeysExcept(keepSeq)

    override fun deleteFailureMacKey(seq: Long) = VaultKeystore.deleteFailureMacKey(seq)

    override fun failureMacKeySeqs(): List<Long> = VaultKeystore.failureMacKeySeqs()

    override fun createFolderKey(keyId: String): SecretKey = VaultKeystore.createFolderKey(keyId)

    override fun folderKey(keyId: String): SecretKey? = VaultKeystore.folderKey(keyId)

    override fun deleteFolderKey(keyId: String) = VaultKeystore.deleteFolderKey(keyId)

    override fun folderKeyIds(): List<String> = VaultKeystore.folderKeyIds()

    override fun deleteAll() = VaultKeystore.deleteAll()

    override fun securityLevel(key: SecretKey): Int = VaultKeystore.securityLevel(key)

    override fun strongBox(): Boolean = VaultKeystore.strongBoxFeature(context)

    override fun probe(): DeviceSecurity = VaultKeystore.probe(context)
}
