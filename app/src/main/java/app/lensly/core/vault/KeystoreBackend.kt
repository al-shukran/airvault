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

    /** Replaces the failure-state MAC key and returns it. */
    fun createFailureMacKey(): SecretKey

    /** The failure-state MAC key, created if it is missing. */
    fun failureMacKey(): SecretKey

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

    override fun createFailureMacKey(): SecretKey = VaultKeystore.createFailureMacKey()

    override fun failureMacKey(): SecretKey = VaultKeystore.failureMacKey()

    override fun deleteAll() = VaultKeystore.deleteAll()

    override fun securityLevel(key: SecretKey): Int = VaultKeystore.securityLevel(key)

    override fun strongBox(): Boolean = VaultKeystore.strongBoxFeature(context)

    override fun probe(): DeviceSecurity = VaultKeystore.probe(context)
}
