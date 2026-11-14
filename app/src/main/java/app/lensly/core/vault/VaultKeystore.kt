package app.lensly.core.vault

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.lensly.BuildConfig
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Collections
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Keystore keys used by the vault. All of them are non-exportable.
 *
 * - [HARDWARE_KEY_ALIAS]: AES-256-GCM. It is the hardware layer that wraps the vault key. It is StrongBox
 *   when available, otherwise TEE. No user-auth binding and no unlocked-device requirement, because the
 *   vault phone may have no lock screen.
 * - `lensly.vault.failure-mac.<seq>`: HMAC-SHA256, one for each Delay State write. It authenticates the failure-delay state
 *   with that sequence number. The keys of earlier writes are deleted, so an older state no longer verifies (see [FailureStore]).
 */
internal object VaultKeystore {
    const val HARDWARE_KEY_ALIAS = "lensly.vault.hw"

    /** Every Delay State MAC key alias starts with this. */
    private const val FAILURE_MAC_PREFIX = "lensly.vault.failure-mac"

    /** The one Delay State MAC key of the layout before per-save keys. It is read only to migrate, and then deleted. */
    private const val LEGACY_FAILURE_MAC_ALIAS = FAILURE_MAC_PREFIX
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

    /**
     * Creates the Delay State MAC key for [seq], replacing any key under that number. It is StrongBox when the device has one,
     * and the TEE otherwise. As for the vault key, a key that is not hardware-backed is refused in a release build.
     */
    fun createFailureMacKey(seq: Long): SecretKey {
        val alias = failureMacAlias(seq)
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
            throw GeneralSecurityException("The Delay State MAC key is not hardware-backed.")
        }
        return key
    }

    /** The Delay State MAC key for [seq], or null once it has been deleted. */
    fun failureMacKey(seq: Long): SecretKey? = get(failureMacAlias(seq))

    /** The legacy single Delay State MAC key, or null once it has been deleted. */
    fun legacyFailureMacKey(): SecretKey? = get(LEGACY_FAILURE_MAC_ALIAS)

    /** Deletes every Delay State MAC key except the one for [keepSeq], and the legacy key too. A null [keepSeq] deletes them all. */
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

    /** Removes every key the vault uses on this device. */
    fun deleteAll() {
        delete(HARDWARE_KEY_ALIAS)
        deleteFailureMacKeysExcept(keepSeq = null)
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

    private fun failureMacAlias(seq: Long): String = "$FAILURE_MAC_PREFIX.$seq"

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
