package app.lensly.core.vault

import android.content.Context
import app.lensly.BuildConfig
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of [Vault.create] and [Vault.rekey]. */
sealed interface CreateResult {
    /** The vault now exists (create), or it accepts the new password (rekey). */
    data object Created : CreateResult

    /** The password failed [PasswordPolicy]. Nothing was written. */
    data class PolicyRejected(val reason: PolicyReason) : CreateResult

    /**
     * The device's Keystore could not create the vault's keys, or its key is software-only in a release build. Nothing
     * was written.
     */
    data object DeviceUnsupported : CreateResult
}

/** Outcome of [Vault.unlock]. */
sealed interface UnlockResult {
    data class Unlocked(val session: VaultSession) : UnlockResult

    /**
     * The password was wrong, or a failure delay is running. Both cases return this same value, and it carries
     * no timing or remaining-delay information.
     */
    data object Rejected : UnlockResult
}

/** What the device's Keystore provides for the vault key. */
data class DeviceSecurity(val strongBox: Boolean, val hardwareBacked: Boolean)

/** The vault's stored state is damaged, or its hardware key can no longer unwrap the vault key. */
class VaultException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The App Password vault.
 *
 * The Vault Key is protected by two layers, and both are needed to recover it:
 * 1. Software: `K_sw = Argon2id(password)`, used with AES-256-GCM to wrap the hardware blob.
 * 2. Hardware: a non-exportable Keystore AES-256-GCM key (`lensly.vault.hw`) wraps the Vault Key.
 *
 * [unlock] rate-limits password checks with [FailureDelay]. While a delay runs, [unlock] returns
 * [UnlockResult.Rejected] without checking the password. It still spends the same Argon2 time as a real check,
 * so the delay cannot be seen from timing.
 *
 * Argon2 runs on [Dispatchers.Default]. File and Keystore work runs on [Dispatchers.IO]. The password arrays
 * passed to [create], [unlock] and [rekey] are zeroed before those calls return.
 *
 * @param clock time source for the failure delay. Tests can inject a clock they control.
 */
class Vault internal constructor(
    context: Context,
    private val clock: Clock,
    private val kdfOverride: KdfParams?,
) {
    constructor(context: Context, clock: Clock = RealClock) : this(context, clock, null)

    private val appContext: Context = context.applicationContext
    private val layout = VaultLayout(File(appContext.noBackupFilesDir, "vault"))
    private val mutex = Mutex()
    private val random = SecureRandom()

    /** True once a vault has been created and not wiped. */
    fun exists(): Boolean = layout.exists()

    /**
     * True when this device may create a vault, under [KeystorePolicy]. The answer comes from the same probe as
     * [deviceSecurity], so it is meaningful only while no vault exists.
     */
    fun canCreate(): Boolean =
        KeystorePolicy.verdict(deviceSecurity().hardwareBacked, BuildConfig.DEBUG) != KeystoreVerdict.Refused

    /**
     * Creates a vault protected by [password], which must pass [PasswordPolicy].
     * Argon2 parameters are calibrated for this device, unless the vault was built with fixed test parameters.
     * In a release build the vault key must be hardware-backed, or the result is [CreateResult.DeviceUnsupported].
     *
     * @throws IllegalStateException if a vault already exists. Call [wipe] first.
     */
    suspend fun create(password: CharArray): CreateResult =
        withContext(Dispatchers.IO) { mutex.withLock { createLocked(password) } }

    /**
     * Unlocks the vault with [password].
     *
     * Returns [UnlockResult.Rejected] for a wrong password, and for any attempt made during a failure delay.
     * A delay attempt does not check the password and does not add to the failure count, but it spends the
     * same Argon2 time as a real check.
     *
     * @throws VaultException if the password is correct but the stored state is damaged or the hardware key is gone.
     */
    suspend fun unlock(password: CharArray): UnlockResult =
        withContext(Dispatchers.IO) { mutex.withLock { unlockLocked(password) } }

    /**
     * Replaces the password of an unlocked vault. Only the wrapped vault key file changes, so wallet data is untouched.
     * The new file is written to a temp file, read back and checked, and only then renamed into place.
     */
    suspend fun rekey(session: VaultSession, newPassword: CharArray): CreateResult =
        withContext(Dispatchers.IO) { mutex.withLock { rekeyLocked(session, newPassword) } }

    /**
     * Deletes the vault: its Keystore keys first, then every file. Call this only on an explicit user command.
     * Do not call it while [create], [unlock] or [rekey] is running.
     */
    fun wipe() {
        // Delete the keys first. Without them the wrapped vault key is unrecoverable even if some file survives.
        VaultKeystore.deleteAll()
        FailureStore.forget(layout.root)
        layout.root.deleteRecursively()
    }

    /** The Keystore capabilities recorded when the vault was created. Without a vault, the device is probed. */
    fun deviceSecurity(): DeviceSecurity {
        if (layout.exists()) {
            val header = VaultHeader.decode(layout.header.readBytes())
            return DeviceSecurity(strongBox = header.strongBox, hardwareBacked = header.hardwareBacked)
        }
        return VaultKeystore.probe(appContext)
    }

    /** The failure count as currently stored. For tests only. */
    internal fun failureCountForTesting(): Int = failureStore().load(clock).failures

    private suspend fun createLocked(password: CharArray): CreateResult {
        try {
            val policy = PasswordPolicy.check(password)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(!layout.exists()) { "A vault already exists. Call wipe() before creating another." }

            val hardwareKey = keystoreOrNull { VaultKeystore.createHardwareKey() }
                ?: return CreateResult.DeviceUnsupported
            val macKey = keystoreOrNull { VaultKeystore.createFailureMacKey() }
                ?: return CreateResult.DeviceUnsupported
            val securityLevel = keystoreOrNull { VaultKeystore.securityLevel(hardwareKey) } ?: UNKNOWN_SECURITY_LEVEL
            val verdict = KeystorePolicy.verdict(
                hardwareBacked = VaultKeystore.isHardwareBacked(securityLevel),
                isDebugBuild = BuildConfig.DEBUG,
            )
            if (verdict == KeystoreVerdict.Refused) {
                // Nothing else has been written yet, so the keys just made are removed and no vault is created.
                keystoreOrNull { VaultKeystore.deleteAll() }
                return CreateResult.DeviceUnsupported
            }

            val salt = ByteArray(VaultHeader.SALT_BYTES).also { random.nextBytes(it) }
            val vaultKey = ByteArray(VAULT_KEY_BYTES).also { random.nextBytes(it) }
            try {
                val kdf = kdfOverride ?: withContext(Dispatchers.Default) { calibrateKdf() }
                val kSw = withContext(Dispatchers.Default) { Argon2Kdf.derive(password, salt, kdf) }
                try {
                    val hardwareWrapped = keystore(HARDWARE_FAILURE) {
                        Aead.sealWithKeystore(hardwareKey, HARDWARE_AAD, vaultKey)
                    }
                    val wrapped = VaultKeyFile.encode(Aead.seal(kSw, SOFTWARE_AAD, hardwareWrapped))

                    layout.root.mkdirs()
                    FailureStore(layout.root, macKey).initialize(clock)
                    writeAtomically(layout.vaultKey, wrapped)
                    // The header goes last. Its presence is what makes exists() true, so a crash earlier leaves no vault.
                    val header = VaultHeader(
                        kdf = kdf,
                        salt = salt,
                        createdAtWallMs = clock.wallMillis(),
                        strongBox = VaultKeystore.strongBoxFeature(appContext),
                        hardwareBacked = VaultKeystore.isHardwareBacked(securityLevel),
                        keySecurityLevel = securityLevel,
                    )
                    writeAtomically(layout.header, header.encode())
                    return CreateResult.Created
                } finally {
                    Arrays.fill(kSw, 0)
                }
            } finally {
                Arrays.fill(vaultKey, 0)
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private suspend fun unlockLocked(password: CharArray): UnlockResult {
        try {
            if (!layout.exists()) return UnlockResult.Rejected
            val header = VaultHeader.decode(layout.header.readBytes())
            val store = failureStore()
            val state = store.load(clock)
            // During a delay the password is not checked, so it counts as neither a pass nor a failure. The KDF still
            // runs once, on a fixed input, so a refused attempt takes as long as a real one.
            if (FailureDelay(header.createdAtWallMs).isBlocked(state, clock)) {
                spendKdfTime(header)
                return UnlockResult.Rejected
            }

            val kSw = withContext(Dispatchers.Default) { Argon2Kdf.derive(password, header.salt, header.kdf) }
            try {
                val hardwareWrapped = try {
                    Aead.open(kSw, SOFTWARE_AAD, VaultKeyFile.decode(layout.vaultKey.readBytes()))
                } catch (e: AEADBadTagException) {
                    store.recordFailure(state, clock)
                    return UnlockResult.Rejected
                }
                // The software layer authenticated, so the password is right. Reset the count before the hardware step.
                store.recordSuccess(state)
                val vaultKey = unwrapWithHardware(hardwareWrapped)
                return UnlockResult.Unlocked(VaultSession(layout, vaultKey))
            } finally {
                Arrays.fill(kSw, 0)
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private suspend fun rekeyLocked(session: VaultSession, newPassword: CharArray): CreateResult {
        try {
            val policy = PasswordPolicy.check(newPassword)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(layout.exists()) { "There is no vault to rekey." }

            val header = VaultHeader.decode(layout.header.readBytes())
            val vaultKey = session.copyOfVaultKey()
            try {
                val softKey = withContext(Dispatchers.Default) { Argon2Kdf.derive(newPassword, header.salt, header.kdf) }
                try {
                    val hardwareWrapped = keystore(HARDWARE_FAILURE) {
                        Aead.sealWithKeystore(VaultKeystore.hardwareKey(), HARDWARE_AAD, vaultKey)
                    }
                    val wrapped = VaultKeyFile.encode(Aead.seal(softKey, SOFTWARE_AAD, hardwareWrapped))
                    val temp = writeTemp(layout.vaultKey, wrapped)
                    if (!verifyWrapped(temp, softKey, vaultKey)) {
                        temp.delete()
                        throw VaultException("The new vault key file did not verify, so the vault was not changed.")
                    }
                    commitTemp(temp, layout.vaultKey)
                    return CreateResult.Created
                } finally {
                    Arrays.fill(softKey, 0)
                }
            } finally {
                Arrays.fill(vaultKey, 0)
            }
        } finally {
            Arrays.fill(newPassword, '\u0000')
        }
    }

    /** Reads [file] back and checks that it unwraps to [expected] with [softKey] and the hardware key. */
    private fun verifyWrapped(file: File, softKey: ByteArray, expected: ByteArray): Boolean {
        return try {
            val hardwareWrapped = Aead.open(softKey, SOFTWARE_AAD, VaultKeyFile.decode(file.readBytes()))
            val unwrapped = keystore(HARDWARE_FAILURE) {
                Aead.openWithKeystore(VaultKeystore.hardwareKey(), HARDWARE_AAD, hardwareWrapped)
            }
            try {
                MessageDigest.isEqual(unwrapped, expected)
            } finally {
                Arrays.fill(unwrapped, 0)
            }
        } catch (e: AEADBadTagException) {
            false
        } catch (e: VaultException) {
            false
        }
    }

    private fun unwrapWithHardware(hardwareWrapped: ByteArray): ByteArray = keystore(HARDWARE_FAILURE) {
        Aead.openWithKeystore(VaultKeystore.hardwareKey(), HARDWARE_AAD, hardwareWrapped)
    }

    /** One timed calibration derivation. Runs on [Dispatchers.Default]. */
    private fun calibrateKdf(): KdfParams = KdfCalibration.calibrate { params ->
        val probePassword = CALIBRATION_PASSWORD.toCharArray()
        val salt = ByteArray(VaultHeader.SALT_BYTES).also { random.nextBytes(it) }
        val started = System.nanoTime()
        val out = Argon2Kdf.derive(probePassword, salt, params)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        Arrays.fill(out, 0)
        elapsedMs
    }

    /**
     * One derivation with the vault's own parameters and salt, on a fixed input that is not secret. The result is
     * discarded. Runs on [Dispatchers.Default].
     */
    private suspend fun spendKdfTime(header: VaultHeader) {
        val fixedInput = CALIBRATION_PASSWORD.toCharArray()
        try {
            val out = withContext(Dispatchers.Default) { Argon2Kdf.derive(fixedInput, header.salt, header.kdf) }
            Arrays.fill(out, 0)
        } finally {
            Arrays.fill(fixedInput, '\u0000')
        }
    }

    private fun failureStore(): FailureStore = FailureStore(layout.root, VaultKeystore.failureMacKey())

    private companion object {
        const val VAULT_KEY_BYTES = 32
        const val UNKNOWN_SECURITY_LEVEL = -1
        const val HARDWARE_FAILURE = "The vault's hardware key cannot unwrap the vault key."
        const val CALIBRATION_PASSWORD = "lensly calibration"

        /** AAD values that keep the two wrapping layers apart. */
        val HARDWARE_AAD = "lensly.vault.key.hw.v1".toByteArray(Charsets.US_ASCII)
        val SOFTWARE_AAD = "lensly.vault.key.sw.v1".toByteArray(Charsets.US_ASCII)

        inline fun <T> keystoreOrNull(block: () -> T): T? = try {
            block()
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        }

        inline fun <T> keystore(message: String, block: () -> T): T = try {
            block()
        } catch (e: GeneralSecurityException) {
            throw VaultException(message, e)
        } catch (e: ProviderException) {
            throw VaultException(message, e)
        }
    }
}
