package app.lensly.core.vault

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A [KeystoreBackend] whose keys live in JVM memory. The Android Keystore is not available to unit tests, so the vault's
 * logic runs against these keys, and the device-only parts are covered by the instrumented tests.
 */
internal class MemoryKeystore : KeystoreBackend {
    private var hardware: SecretKey? = null
    private val failureMacs = mutableMapOf<Long, SecretKey>()
    private var legacyFailureMac: SecretKey? = null

    override fun createHardwareKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { hardware = it }

    override fun hardwareKey(): SecretKey =
        hardware ?: throw VaultException("The vault's hardware key is missing from the Keystore.")

    override fun createFailureMacKey(seq: Long): SecretKey = newHmacKey().also { failureMacs[seq] = it }

    override fun failureMacKey(seq: Long): SecretKey? = failureMacs[seq]

    override fun legacyFailureMacKey(): SecretKey? = legacyFailureMac

    override fun deleteFailureMacKeysExcept(keepSeq: Long?) {
        failureMacs.keys.removeAll { it != keepSeq }
        legacyFailureMac = null
    }

    /** Makes the single key of the layout before per-save keys, so a test can write a legacy record under it. */
    fun createLegacyFailureMacKey(): SecretKey = newHmacKey().also { legacyFailureMac = it }

    /** How many Delay State MAC keys exist, the legacy key included. */
    fun failureMacKeyCount(): Int = failureMacs.size + if (legacyFailureMac != null) 1 else 0

    override fun deleteAll() {
        hardware = null
        failureMacs.clear()
        legacyFailureMac = null
    }

    override fun securityLevel(key: SecretKey): Int = TRUSTED_ENVIRONMENT

    override fun strongBox(): Boolean = false

    override fun probe(): DeviceSecurity = DeviceSecurity(strongBox = false, hardwareBacked = true)

    private fun newHmacKey(): SecretKey = KeyGenerator.getInstance("HmacSHA256").generateKey()

    private companion object {
        /** `KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, so the vault treats the test key as hardware-backed. */
        const val TRUSTED_ENVIRONMENT = 1
    }
}
