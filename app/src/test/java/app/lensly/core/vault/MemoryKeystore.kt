package app.lensly.core.vault

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A [KeystoreBackend] whose keys live in JVM memory. The Android Keystore is not available to unit tests, so the vault's
 * logic runs against these keys, and the device-only parts are covered by the instrumented tests.
 */
internal class MemoryKeystore : KeystoreBackend {
    private var hardware: SecretKey? = null
    private var failureMac: SecretKey? = null

    override fun createHardwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { hardware = it }

    override fun hardwareKey(): SecretKey =
        hardware ?: throw VaultException("The vault's hardware key is missing from the Keystore.")

    override fun createFailureMacKey(): SecretKey =
        KeyGenerator.getInstance("HmacSHA256").generateKey().also { failureMac = it }

    override fun failureMacKey(): SecretKey = failureMac ?: createFailureMacKey()

    override fun deleteAll() {
        hardware = null
        failureMac = null
    }

    override fun securityLevel(key: SecretKey): Int = TRUSTED_ENVIRONMENT

    override fun strongBox(): Boolean = false

    override fun probe(): DeviceSecurity = DeviceSecurity(strongBox = false, hardwareBacked = true)

    private companion object {
        /** `KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, so the vault treats the test key as hardware-backed. */
        const val TRUSTED_ENVIRONMENT = 1
    }
}
