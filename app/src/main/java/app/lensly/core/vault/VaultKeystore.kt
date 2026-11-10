package app.lensly.core.vault

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Keystore keys used by the vault. All of them are non-exportable.
 *
 * - [HARDWARE_KEY_ALIAS]: AES-256-GCM. It is the hardware layer that wraps the vault key. It is StrongBox
 *   when available, otherwise TEE. No user-auth binding and no unlocked-device requirement, because the
 *   vault phone may have no lock screen.
 * - [FAILURE_MAC_ALIAS]: HMAC-SHA256. It authenticates the failure-delay state.
 */
internal object VaultKeystore {
    const val HARDWARE_KEY_ALIAS = "lensly.vault.hw"
    const val FAILURE_MAC_ALIAS = "lensly.vault.failure-mac"
    private const val PROBE_ALIAS = "lensly.vault.probe"
    private const val PROVIDER = "AndroidKeyStore"

    /** Replaces the hardware wrapping key. Tries StrongBox first and falls back to the TEE. */
    fun createHardwareKey(): SecretKey {
        delete(HARDWARE_KEY_ALIAS)
        return generateAes(HARDWARE_KEY_ALIAS)
    }

    /** The existing hardware wrapping key. Throws if it has been removed. */
    fun hardwareKey(): SecretKey =
        get(HARDWARE_KEY_ALIAS) ?: throw VaultException("The vault's hardware key is missing from the Keystore.")

    /** Replaces the failure-state MAC key. */
    fun createFailureMacKey(): SecretKey {
        delete(FAILURE_MAC_ALIAS)
        val spec = KeyGenParameterSpec.Builder(FAILURE_MAC_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    /** The failure-state MAC key, created if it is missing. A new key cannot verify old state, so the state reads as tampered. */
    fun failureMacKey(): SecretKey = get(FAILURE_MAC_ALIAS) ?: createFailureMacKey()

    /** Removes every key the vault uses on this device. */
    fun deleteAll() {
        delete(HARDWARE_KEY_ALIAS)
        delete(FAILURE_MAC_ALIAS)
        delete(PROBE_ALIAS)
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

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun get(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun delete(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun generateAes(alias: String): SecretKey = try {
        generateAes(alias, strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        generateAes(alias, strongBox = false)
    } catch (e: ProviderException) {
        if (e.cause is StrongBoxUnavailableException) generateAes(alias, strongBox = false) else throw e
    }

    private fun generateAes(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }
}
