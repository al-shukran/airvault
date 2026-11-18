package app.lensly.core.vault

import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * A [KeystoreBackend] whose keys live in JVM memory. The Android Keystore is not available to unit tests, so the vault's
 * logic runs against these keys, and the device-only parts are covered by the instrumented tests.
 */
class MemoryKeystore : KeystoreBackend {
    private var hardware: SecretKey? = null
    private var slotKey: SecretKey? = null
    private val failureMacs = mutableMapOf<Long, SecretKey>()
    private val folderKeys = mutableMapOf<String, SecretKey>()

    /** When true, deleting a folder key fails, as a Keystore error would. The key stays where it was. */
    var failFolderKeyDeletes = false

    /** When true, using the hardware key needs a device authentication first, as a user-auth Keystore key does. */
    var hardwareAuthRequired = false

    /** When true, the hardware key is gone for good, as after the screen lock was removed. */
    var hardwareKeyInvalidated = false

    override fun createHardwareKey(): SecretKey = newAesKey().also { hardware = it }

    override fun hardwareKey(): SecretKey {
        if (hardwareKeyInvalidated) throw VaultKeyInvalidatedException("The hardware key was invalidated.")
        if (hardwareAuthRequired) throw VaultAuthRequiredException("The hardware key needs a device authentication.")
        return hardware ?: throw VaultException("The vault's hardware key is missing from the Keystore.")
    }

    /** Replaces the slot key. A vault sealed under the old key no longer opens, which is how a test shows the binding. */
    override fun createSlotKey() {
        slotKey = newHmacKey()
    }

    override fun slotMac(message: ByteArray): ByteArray {
        val key = slotKey ?: throw VaultException("The vault's slot key is missing from the Keystore.")
        return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(message)
    }

    override fun createFailureMacKey(seq: Long): SecretKey = newHmacKey().also { failureMacs[seq] = it }

    override fun failureMacKey(seq: Long): SecretKey? = failureMacs[seq]

    override fun deleteFailureMacKeysExcept(keepSeq: Long?) {
        failureMacs.keys.removeAll { it != keepSeq }
    }

    override fun deleteFailureMacKey(seq: Long) {
        failureMacs.remove(seq)
    }

    override fun failureMacKeySeqs(): List<Long> = failureMacs.keys.toList()

    override fun createFolderKey(keyId: String): SecretKey = newAesKey().also { folderKeys[keyId] = it }

    override fun folderKey(keyId: String): SecretKey? = folderKeys[keyId]

    override fun deleteFolderKey(keyId: String) {
        if (failFolderKeyDeletes) throw VaultException("The Keystore refused to delete the key.")
        folderKeys.remove(keyId)
    }

    override fun folderKeyIds(): List<String> = folderKeys.keys.toList()

    /** How many Delay State MAC keys exist. */
    fun failureMacKeyCount(): Int = failureMacs.size

    /** Puts [key] back under [keyId], as if the Keystore had never deleted it. For a test of what a deleted key would have opened. */
    fun restoreFolderKey(keyId: String, key: SecretKey) {
        folderKeys[keyId] = key
    }

    override fun deleteAll() {
        hardware = null
        slotKey = null
        failureMacs.clear()
        folderKeys.clear()
    }

    override fun securityLevel(key: SecretKey): Int = TRUSTED_ENVIRONMENT

    override fun strongBox(): Boolean = false

    override fun probe(): DeviceSecurity = DeviceSecurity(strongBox = false, hardwareBacked = true)

    private fun newAesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun newHmacKey(): SecretKey = KeyGenerator.getInstance("HmacSHA256").generateKey()

    private companion object {
        /** `KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, so the vault treats the test key as hardware-backed. */
        const val TRUSTED_ENVIRONMENT = 1
    }
}
