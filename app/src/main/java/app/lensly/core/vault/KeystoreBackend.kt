package app.lensly.core.vault

import android.content.Context
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

    /** Removes every key the vault uses on this device. */
    fun deleteAll()

    /** The `KeyInfo.securityLevel` that the Keystore reports for [key]. */
    fun securityLevel(key: SecretKey): Int

    /** True when the device has a StrongBox. */
    fun strongBox(): Boolean

    /** The device's Keystore capabilities, probed without a vault. */
    fun probe(): DeviceSecurity
}

/** The app's backend: the non-exportable keys in the Android Keystore, through [VaultKeystore]. */
internal class AndroidKeystoreBackend(private val context: Context) : KeystoreBackend {
    override fun createHardwareKey(): SecretKey = VaultKeystore.createHardwareKey()

    override fun hardwareKey(): SecretKey = VaultKeystore.hardwareKey()

    override fun createFailureMacKey(seq: Long): SecretKey = VaultKeystore.createFailureMacKey(seq)

    override fun failureMacKey(seq: Long): SecretKey? = VaultKeystore.failureMacKey(seq)

    override fun legacyFailureMacKey(): SecretKey? = VaultKeystore.legacyFailureMacKey()

    override fun deleteFailureMacKeysExcept(keepSeq: Long?) = VaultKeystore.deleteFailureMacKeysExcept(keepSeq)

    override fun deleteAll() = VaultKeystore.deleteAll()

    override fun securityLevel(key: SecretKey): Int = VaultKeystore.securityLevel(key)

    override fun strongBox(): Boolean = VaultKeystore.strongBoxFeature(context)

    override fun probe(): DeviceSecurity = VaultKeystore.probe(context)
}
