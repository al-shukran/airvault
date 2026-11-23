package app.lensly.core.vault

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import app.lensly.shared.BuildConfig
import app.lensly.ui.isEmulator
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
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

    /** The packaged app allows Android to back up its data, so this build never creates a vault. Nothing was written. */
    data object BackupAllowed : CreateResult
}

/** Outcome of [Vault.unlock]. */
sealed interface UnlockResult {
    data class Unlocked(val session: VaultSession) : UnlockResult

    /**
     * The password was wrong, a failure delay is running, or the try could not be counted. Every case returns this same value, and it
     * carries no timing or remaining-delay information.
     */
    data object Rejected : UnlockResult

    /**
     * The password was wrong, and that try was the last one the owner allows, so the vault was erased. Nothing is left to open,
     * and the caller leaves the vault as it would after a wipe.
     */
    data object Erased : UnlockResult
}

/** Outcome of [Vault.setFailureLimits]. */
sealed interface FailureCapResult {
    /**
     * The password was this vault's own, and the change is accepted. From the decoy, nothing is written, so the change appears
     * to succeed without reaching the real cap.
     */
    data object Saved : FailureCapResult

    /** The password was not this vault's own, or a delay is running. Nothing was saved. */
    data object Rejected : FailureCapResult

    /** The password was wrong, and that try was the last one the owner allows, so the vault was erased. */
    data object Erased : FailureCapResult
}

/** Outcome of [Vault.setDuress]. Only [Enabled] changes the real vault. Every other outcome leaves it as it was. */
sealed interface DuressResult {
    /** The Duress Password is set. It opens a Decoy Vault that holds at least one wallet. */
    data object Enabled : DuressResult

    /** The Duress Password failed [PasswordPolicy]. */
    data class PolicyRejected(val reason: PolicyReason) : DuressResult

    /** The Duress Password is the App Password. One password must never open both vaults. */
    data object SameAsAppPassword : DuressResult

    /** The populate step left the Decoy Vault without a wallet, so the Duress Password was not enabled. */
    data object DecoyEmpty : DuressResult

    /** The new vault key file could not be written and read back, or the decoy could not be made. */
    data object Failed : DuressResult
}

/** What the device's Keystore provides for the vault key. */
data class DeviceSecurity(val strongBox: Boolean, val hardwareBacked: Boolean)

/** The vault's stored state is damaged, or its hardware key can no longer unwrap the vault key. */
open class VaultException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The hardware key needs a fresh device authentication, and the owner did not give one. The vault asks first, so a caller sees
 * this only after a cancel.
 */
class VaultAuthRequiredException(message: String, cause: Throwable? = null) : VaultException(message, cause)

/** Android deleted the hardware key, because the screen lock was removed or changed. The vault cannot be opened any more. */
class VaultKeyInvalidatedException(message: String, cause: Throwable? = null) : VaultException(message, cause)

/** The error for a Keystore refusal: a missing device authentication, a lost key, or any other failure. */
internal fun keystoreFailure(message: String, e: GeneralSecurityException): VaultException = when (e) {
    is UserNotAuthenticatedException -> VaultAuthRequiredException(message, e)
    is KeyPermanentlyInvalidatedException -> VaultKeyInvalidatedException(message, e)
    else -> VaultException(message, e)
}

/** What the gate says for a vault in another format. It names no secret. */
const val VAULT_FORMAT_CHANGED = "The vault format changed. Restore your wallets from their seed phrases."

/** What the gate says for vault files without a whole vault. It names no secret. */
const val VAULT_DAMAGED =
    "The vault files are incomplete, so this device cannot open or create a vault. Clear the app's data to start again."

/**
 * The vault on disk is in another format than this build reads. Nothing is checked against a password, and nothing is migrated: the owner
 * restores the wallets from their seed phrases into a new vault.
 */
class VaultFormatChangedException : VaultException(VAULT_FORMAT_CHANGED)

/**
 * The App Password vault, and its optional Duress Password.
 *
 * The Vault Key is protected by three keys, and all are needed to recover it:
 * 1. Slot: a non-exportable Keystore HMAC-SHA256 key (`lensly.vault.slot-mac`) binds every guess to this device.
 * 2. Software: `K_sw = HKDF-SHA256(Argon2id(password, salt) || HMAC(slot key, salt), info = "lensly-vault-slot-v3")`, used with
 *    AES-256-GCM to wrap the hardware blob. Each guess needs the slot key, so copies of the vault files cannot be tested offline.
 * 3. Hardware: a non-exportable Keystore AES-256-GCM key (`lensly.vault.hw`) wraps the Vault Key.
 *
 * `vault.key` holds two slots: one for the real vault and one for the Decoy Vault that a Duress Password opens. Its size
 * never changes (see [VaultKeyFile]). [unlock] derives `K_sw` once and tries it on both slots, so the Duress Password
 * costs what the App Password costs, and a refused password costs the same. Storage and timing do not say which vault
 * opened. Only [VaultSession.isDecoy] says so, and the app must never show it.
 *
 * [unlock] rate-limits password checks with [FailureDelay], and both passwords share that delay. The cap is stored in the
 * failure state, so it is the same for both. While a delay runs, [unlock] returns [UnlockResult.Rejected] without checking
 * the password. It still spends the same Argon2 time as a real check, so the delay cannot be seen from timing. The same count
 * erases the vault after the owner's number of wrong tries in a row, with the same wipe as [wipe]. The erasing try returns
 * [UnlockResult.Erased], and both vaults go, since the wrong tries do not say which one was meant. When no copy of the failure state
 * verifies, the next attempt erases the vault before the password is checked, since the count is unknown (see [FailureDelay]).
 * Each try is counted before its password is checked, and a try whose count cannot be saved is refused without a verdict (see
 * [checkPasswordLocked]).
 *
 * Argon2 runs on [Dispatchers.Default]. File and Keystore work runs on [Dispatchers.IO]. The password arrays passed to
 * [create], [unlock], [rekey], [setDuress] and [setFailureLimits] are zeroed before those calls return.
 *
 * @param clock time source for the failure delay. Tests can inject a clock they control.
 */
class Vault constructor(
    private val layout: VaultLayout,
    private val keys: KeystoreBackend,
    private val clock: Clock,
    private val kdfOverride: KdfParams?,
    /** True when the packaged app allows backup. Such a build refuses to create a vault, see [refusal]. */
    private val backupAllowed: Boolean = false,
    private val readBootCount: () -> Int? = { null },
    /** Asks the owner for a device authentication when the hardware key needs one. True when they pass. */
    private val authorize: suspend () -> Boolean = { false },
) {
    constructor(context: Context, clock: Clock = RealClock) : this(context, clock, null)

    constructor(context: Context, clock: Clock, kdfOverride: KdfParams?) : this(context, clock, kdfOverride, hardwareKeyUserAuth = true)

    /**
     * [hardwareKeyUserAuth] is false only for [app.lensly.core.vault.instrumentedVault]. Android refuses to make a key that needs a
     * screen lock on a device without one, and the emulators that run the instrumented tests have none. The app passes true.
     */
    internal constructor(
        context: Context,
        clock: Clock,
        kdfOverride: KdfParams?,
        hardwareKeyUserAuth: Boolean,
    ) : this(
        layout = VaultLayout.of(context),
        keys = AndroidKeystoreBackend(context.applicationContext, hardwareKeyUserAuth),
        clock = clock,
        kdfOverride = kdfOverride,
        readBootCount = { bootCountOf(context.applicationContext) },
        backupAllowed = BackupExclusion.allowsBackup(context.applicationInfo.flags),
        authorize = { confirmDeviceCredential(context.applicationContext) },
    )

    private val mutex = Mutex()
    private val random = SecureRandom()

    /** True once a vault has been created and not wiped. */
    fun exists(): Boolean = layout.exists()

    /** True when vault files are left without a whole vault. The gate then shows [VAULT_DAMAGED], and [create] refuses. */
    fun isPartial(): Boolean = layout.isPartial()

    /** True before the first setup, when there is no vault and no vault file. See [VaultLayout.isBeforeSetup]. */
    fun isBeforeSetup(): Boolean = layout.isBeforeSetup()

    /**
     * True when the vault on disk is in another format than this build reads. [unlock] refuses such a vault before it checks any password,
     * and the gate shows [VAULT_FORMAT_CHANGED] instead of the password screen. It reads only `header.bin`.
     */
    fun formatChanged(): Boolean = try {
        VaultHeader.decode(readVaultFile(layout.header))
        false
    } catch (e: VaultFormatChangedException) {
        true
    } catch (e: VaultException) {
        false
    }

    /**
     * True when this device may create a vault, under [KeystorePolicy], and this build may create one under [refusal]. The
     * answer comes from the same probe as [deviceSecurity], so it is meaningful only while no vault exists.
     */
    fun canCreate(): Boolean =
        refusal() == null &&
            KeystorePolicy.verdict(deviceSecurity().hardwareBacked, BuildConfig.DEBUG, deviceIsEmulator()) !=
            KeystoreVerdict.Refused

    /**
     * Why this build may not create a vault, or null when it may. A build whose packaged manifest allows backup is refused, because
     * its vault could leave the device. The text is [BackupExclusion.REFUSAL], which holds no secret.
     */
    fun refusal(): String? = if (backupAllowed) BackupExclusion.REFUSAL else null

    /**
     * Creates a vault protected by [password], which must pass [PasswordPolicy]. The decoy slot starts as random bytes.
     * Argon2 parameters are calibrated for this device, unless the vault was built with fixed test parameters. In a
     * release build the vault key must be hardware-backed, or the result is [CreateResult.DeviceUnsupported]. A build that allows
     * backup gets [CreateResult.BackupAllowed] before anything is checked or written.
     *
     * @throws IllegalStateException if a vault already exists, or vault files are left without one (see [isPartial]). Call [wipe] first.
     */
    suspend fun create(password: CharArray): CreateResult =
        withContext(Dispatchers.IO) { mutex.withLock { createLocked(password) } }

    /**
     * Unlocks the vault with [password]. The App Password opens the real vault, and a Duress Password opens the Decoy
     * Vault. The session's [VaultSession.isDecoy] says which one opened.
     *
     * Returns [UnlockResult.Rejected] for a password that opens neither, and for any attempt made during a failure delay.
     * A delay attempt does not check the password and does not add to the failure count, but it spends the same Argon2
     * time as a real check. A wrong try that is the owner's last allowed one erases the vault and returns [UnlockResult.Erased].
     * A try whose count cannot be saved is refused the same way, before its password is checked.
     *
     * @throws VaultException if the password is correct but the stored state is damaged or the hardware key is gone.
     */
    suspend fun unlock(password: CharArray): UnlockResult =
        withContext(Dispatchers.IO) { mutex.withLock { unlockLocked(password) } }

    /**
     * Opens the real vault from its Vault Key, for Lumio's biometric unlock, which keeps that key sealed under a biometric key (see
     * [BiometricUnlock]). The key must name a slot of this vault's key file, or nothing opens and the result is null. No password is
     * checked, so no try is counted. The session takes its own copy of [vaultKey], and the caller zeroes its own.
     */
    suspend fun unlockWithVaultKey(vaultKey: ByteArray): VaultSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Biometric tries do not count toward the failure delay or erase count: the OS BIOMETRIC_STRONG lockout already limits them.
                if (!layout.exists()) return@withLock null
                if (VaultKeyFile.ownerIndex(VaultKeyFile.decode(readVaultFile(layout.vaultKey)), vaultKey) < 0) return@withLock null
                openSessionLocked(vaultKey, isDecoy = false)
            }
        }

    /**
     * Saves the Failure Delay cap, [capMs] in milliseconds within [FailureDelay.MIN_CAP_MS] to [FailureDelay.MAX_CAP_MS] or null
     * for Default, and the erase count [wipeAfter] (within [FailureDelay.MIN_WIPE_AFTER] to [FailureDelay.MAX_WIPE_AFTER]), or the
     * saved erase count when [wipeAfter] is null. The password is checked exactly as [unlock] checks it, so a running delay refuses
     * the change and a wrong password counts as a failure. A right one resets the failure count. The limits live in the failure
     * store, which both vaults share. Only the real vault writes them. A call from a decoy session ([inDecoy] true) takes only the
     * Duress Password, and its change is accepted but nothing is written, so a decoy never changes the real limits. The check runs
     * in every case, so a call takes as long as a save. The limits apply before unlock. [password] is zeroed before this returns.
     *
     * @throws IllegalArgumentException if [capMs] or [wipeAfter] is outside the offered range.
     */
    suspend fun setFailureLimits(password: CharArray, capMs: Long?, wipeAfter: Int? = null, inDecoy: Boolean = false): FailureCapResult =
        withContext(Dispatchers.IO) { mutex.withLock { setFailureLimitsLocked(password, capMs, wipeAfter, inDecoy) } }

    /**
     * The saved Failure Delay cap in milliseconds. Null means Default, and it is also the answer when no vault exists, so
     * callers check [exists] first when that difference matters.
     */
    suspend fun failureDelayCapMs(): Long? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!layout.exists()) return@withLock null
                failureStore().load(clock).capMs
            }
        }

    /** The saved erase count, which is [FailureDelay.DEFAULT_WIPE_AFTER] when no vault exists. */
    suspend fun failureEraseAfter(): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!layout.exists()) return@withLock FailureDelay.DEFAULT_WIPE_AFTER
                failureStore().load(clock).wipeAfter
            }
        }

    /**
     * The tries left before this vault is erased, when that is [FailureDelay.WARN_TRIES_LEFT] or fewer, and null otherwise. It
     * counts the wrong tries of both passwords, so it says nothing about which vault a password opens. Null when no vault exists, and
     * after a right password until the next wrong try.
     */
    suspend fun triesBeforeErase(): Int? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!layout.exists()) return@withLock null
                FailureDelay.triesLeftToWarn(failureStore().load(clock))
            }
        }

    /**
     * Replaces the App Password of an unlocked vault. Only the vault's own slot in `vault.key` changes, so wallet data is
     * untouched, and the other slot is kept byte for byte. The new file is written to a temp file, read back and checked,
     * and only then renamed into place.
     *
     * On the real vault, the new password may not open the decoy slot. On a decoy session, which keeps its own entry
     * password, it may not open the real slot. Either way it is refused with the reason of a weak password, which reveals
     * nothing about a second password.
     */
    suspend fun rekey(session: VaultSession, newPassword: CharArray): CreateResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { if (session.isDecoy) rekeyDecoyLocked(session, newPassword) else rekeyLocked(session, newPassword) }
        }

    /**
     * Sets the Duress Password of the real vault, and creates the Decoy Vault it opens. A Duress Password that is already set
     * is replaced, and the old Decoy Vault is removed.
     *
     * [populate] receives the new, empty decoy session, and creates the decoy's wallets there. The decoy must hold at least
     * one wallet before the Duress Password takes effect, or the result is [DuressResult.DecoyEmpty]. Nothing is written until
     * the new decoy is complete. A failure removes what [populate] wrote, so the vault is as it was. [populate] must not use
     * the real session. Call this from a non-cancellable context, so that a cancelled call cannot leave decoy wallets behind.
     *
     * On a decoy session, the same call sets the decoy's own entry password. It keeps the decoy's wallets, and it is refused
     * with [DuressResult.PolicyRejected] if the password opens the real vault. The decoy's [populate] is not run.
     */
    suspend fun setDuress(
        session: VaultSession,
        duressPassword: CharArray,
        populate: (VaultSession) -> Unit,
    ): DuressResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (session.isDecoy) setDuressDecoyLocked(session, duressPassword) else setDuressLocked(session, duressPassword, populate)
            }
        }

    /**
     * Removes the Duress Password. The decoy slot becomes random bytes again, so the Duress Password opens nothing from then on,
     * and the Decoy Vault's wallets and records are deleted. The real vault's files are never touched. Without a Duress
     * Password this changes nothing visible, and the vault cannot tell the difference, so the owner may call it either way.
     * On a decoy session it removes that decoy, which is the same outcome as the real vault's removal.
     *
     * @throws VaultException if the new vault key file cannot be written, in which case nothing changed.
     */
    suspend fun removeDuress(session: VaultSession): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (session.isDecoy) removeOwnDecoyLocked(session, keepEmptyDecoy = false) else removeDuressLocked(session)
            }
        }

    /**
     * Deletes the vault: its Keystore keys first, then every file, the real vault and the decoy vault alike. Call this on an
     * explicit user command, or from the erase that follows the owner's last allowed wrong try (see [unlock]). Do not call it
     * while any other operation of this class is running.
     */
    fun wipe() {
        wipeVault(layout) { keys.deleteAll() }
    }

    /**
     * Deletes only the Decoy Vault's contents, from a decoy session: its wallets and records go, and its slot stays under
     * the same Duress Password. The real vault's slot, wallets and records are never touched, so the App Password still
     * opens it. The Duress Password keeps working, but it opens an empty Decoy Vault, so a refused password never reveals
     * that a decoy existed.
     *
     * The decoy's folders stay, refilled with random bytes, so the store's folder count and file shape do not change.
     *
     * @throws VaultException if the vault key file cannot be changed, in which case nothing changed.
     */
    fun wipeDecoy(session: VaultSession) {
        require(session.isDecoy) { "Only a decoy session can wipe the Decoy Vault." }
        removeOwnDecoyLocked(session, keepEmptyDecoy = true)
    }

    /** The Keystore capabilities recorded when the vault was created. Without a vault, the device is probed. */
    fun deviceSecurity(): DeviceSecurity {
        if (layout.exists()) {
            val header = VaultHeader.decode(layout.header.readBytes())
            return DeviceSecurity(strongBox = header.strongBox, hardwareBacked = header.hardwareBacked)
        }
        return keys.probe()
    }

    /** The failure count as currently stored. For tests only. */
    fun failureCountForTesting(): Int = failureStore().load(clock).failures

    private suspend fun createLocked(password: CharArray): CreateResult {
        try {
            if (backupAllowed) return CreateResult.BackupAllowed
            val policy = PasswordPolicy.check(password)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(!layout.exists()) { "A vault already exists. Call wipe() before creating another." }
            check(!layout.isPartial()) { "Vault files are left without a vault. Call wipe() before creating one." }

            val hardwareKey = keystoreOrNull { keys.createHardwareKey() } ?: return CreateResult.DeviceUnsupported
            val securityLevel = keystoreOrNull { keys.securityLevel(hardwareKey) } ?: UNKNOWN_SECURITY_LEVEL
            val verdict = KeystorePolicy.verdict(
                hardwareBacked = VaultKeystore.isHardwareBacked(securityLevel),
                isDebugBuild = BuildConfig.DEBUG,
                isEmulator = deviceIsEmulator(),
            )
            if (verdict == KeystoreVerdict.Refused) {
                // Nothing else has been written yet, so the keys just made are removed and no vault is created.
                keystoreOrNull { keys.deleteAll() }
                return CreateResult.DeviceUnsupported
            }
            // Every guess at the password runs through this key, so no vault is made without it.
            if (keystoreOrNull { keys.createSlotKey() } == null) {
                keystoreOrNull { keys.deleteAll() }
                return CreateResult.DeviceUnsupported
            }

            val salt = ByteArray(VaultHeader.SALT_BYTES).also { random.nextBytes(it) }
            val vaultKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            try {
                val kdf = kdfOverride ?: withContext(Dispatchers.Default) { calibrateKdf() }
                val softKey = withContext(Dispatchers.Default) { deriveSoftKey(password, salt, kdf) }
                try {
                    val realSlot = withHardwareAuth {
                        sealSlot(softKey, keystore(HARDWARE_FAILURE) { keys.hardwareKey() }, vaultKey, VaultRole.REAL)
                    }
                    val decoySlot = VaultKeyFile.randomSlot(random)
                    layout.root.mkdirs()
                    if (!createFailureState()) {
                        // The hardware key was made a moment ago, and the directory may hold the first Delay State copies.
                        // Both go, so a refused creation leaves nothing behind.
                        keystoreOrNull { keys.deleteAll() }
                        layout.root.deleteRecursively()
                        return CreateResult.DeviceUnsupported
                    }
                    writeAtomically(layout.vaultKey, VaultKeyFile.encode(listOf(realSlot, decoySlot), random))
                    // The store starts with its full set of wallet folders, so it never shows an empty one.
                    WalletPad(layout, keys, vaultKey, REAL_SECTION).padTo(WALLET_SLOT_STEP)
                    // The Review Record pool starts with its first batch, so an empty vault shows the same pool as one with records.
                    ReviewPool(layout.reviewPool, vaultKey).ensureFirstBatch()
                    // The header goes last. Its presence is what makes exists() true, so a crash earlier leaves no vault.
                    val header = VaultHeader(
                        kdf = kdf,
                        salt = salt,
                        createdAtWallMs = clock.wallMillis(),
                        strongBox = keys.strongBox(),
                        hardwareBacked = VaultKeystore.isHardwareBacked(securityLevel),
                        keySecurityLevel = securityLevel,
                    )
                    writeAtomically(layout.header, header.encode())
                    // The photo key is made now, so the cover can seal its first photo before anyone unlocks the vault.
                    bestEffort {
                        val session = VaultSession(
                            layout,
                            keys,
                            vaultKey.copyOf(),
                            pad = WalletPad(layout, keys, vaultKey.copyOf(), REAL_SECTION),
                        )
                        try {
                            // A photo key pair made before the first setup becomes this vault's own, so those photos open through it.
                            session.adoptPendingPhotoKey()
                            session.ensurePhotoKey()
                        } finally {
                            session.close()
                        }
                    }
                    return CreateResult.Created
                } finally {
                    Arrays.fill(softKey, 0)
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
            val opened = when (val checked = checkPasswordLocked(password)) {
                is Checked.Opened -> checked.slot
                Checked.Refused -> return UnlockResult.Rejected
                Checked.Erased -> return UnlockResult.Erased
            }
            try {
                return UnlockResult.Unlocked(openSessionLocked(opened.vaultKey, isDecoy = opened.role == VaultRole.DECOY))
            } finally {
                opened.close()
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    /**
     * The session of the vault under [vaultKey], which is the Decoy Vault when [isDecoy] is true. The session takes its own copy of the
     * key. The steps that bring the files to the layout run here, and a step that fails is run again by the next unlock.
     */
    private fun openSessionLocked(vaultKey: ByteArray, isDecoy: Boolean): VaultSession {
        val session = VaultSession(
            layout,
            keys,
            vaultKey.copyOf(),
            isDecoy = isDecoy,
            pad = WalletPad(layout, keys, vaultKey, if (isDecoy) DECOY_SECTION else REAL_SECTION),
        )
        // The Review Record pool gets its first batch back when it is missing.
        if (!session.isDecoy) bestEffort { session.ensureReviewPool() }
        // The wallet folders and the free-slot list are brought to the layout on every unlock.
        bestEffort { session.ensurePadded() }
        // Each vault's photo key is made on its first unlock, so a vault from before photos were sealed gets one.
        bestEffort { session.ensurePhotoKey() }
        return session
    }

    /**
     * Runs [block], which obtains the hardware key and uses it. When the Keystore refuses for want of a device authentication, the
     * owner is asked once, and [block] runs again. The password is not asked for again, so the retry is cheap. A cancel rethrows.
     */
    private suspend inline fun <T> withHardwareAuth(block: () -> T): T = try {
        block()
    } catch (e: VaultAuthRequiredException) {
        if (!authorize()) throw e
        block()
    }

    /**
     * Runs one unlock step that moves or pads stored files. A step that fails is run again by the next unlock, so its failure must not
     * fail this one: the password is right, and the vault opens.
     */
    private inline fun bestEffort(step: () -> Unit) {
        try {
            step()
        } catch (e: IOException) {
            // The next unlock runs the step again.
        } catch (e: VaultException) {
            // The next unlock runs the step again.
        }
    }

    private suspend fun setFailureLimitsLocked(password: CharArray, capMs: Long?, wipeAfter: Int?, inDecoy: Boolean): FailureCapResult {
        try {
            if (capMs != null) {
                require(capMs in FailureDelay.MIN_CAP_MS..FailureDelay.MAX_CAP_MS) { "cap must be between 10 s and 1 h" }
            }
            if (wipeAfter != null) {
                require(wipeAfter in FailureDelay.MIN_WIPE_AFTER..FailureDelay.MAX_WIPE_AFTER) { "erase count must be between 3 and 20" }
            }
            val opened = when (val checked = checkPasswordLocked(password)) {
                is Checked.Opened -> checked.slot
                Checked.Refused -> return FailureCapResult.Rejected
                Checked.Erased -> return FailureCapResult.Erased
            }
            try {
                // Each vault takes only its own password. The limits are shared by both vaults, so only the real vault writes them. A
                // decoy's change is accepted and nothing is written, so a coerced change cannot weaken the real limits.
                val own = if (inDecoy) VaultRole.DECOY else VaultRole.REAL
                if (opened.role != own) return FailureCapResult.Rejected
                if (!inDecoy) opened.store.recordLimits(opened.state, capMs, wipeAfter ?: opened.state.wipeAfter, clock)
                return FailureCapResult.Saved
            } finally {
                opened.close()
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    /** What one password check found. [Erased] is the check whose wrong try was the owner's last allowed one. */
    private sealed interface Checked {
        /** The password opened [slot]. The caller closes it. */
        class Opened(val slot: OpenedSlot) : Checked

        /** The password opened neither slot, a Failure Delay refused it, or the try could not be counted. Nothing was erased. */
        data object Refused : Checked

        /** The wrong try erased the vault. Nothing is left to open. */
        data object Erased : Checked
    }

    /** A password that opened one slot: its Vault Key and role, and the software key. The caller closes it. */
    private class OpenedSlot(
        val softKey: ByteArray,
        val vaultKey: ByteArray,
        val role: Byte,
        val store: FailureStore,
        val state: FailureState,
    ) {
        /** Zeroes the keys this slot holds. */
        fun close() {
            Arrays.fill(softKey, 0)
            Arrays.fill(vaultKey, 0)
        }
    }

    /**
     * Checks [password] against the Failure Delay and against both slots. The result is [Checked.Refused] when the attempt is refused:
     * during a delay, which is not checked and not counted, or when the try cannot be counted, which is found before the password is
     * checked. Otherwise the try is counted first, so a try that a restart or an error cuts short still counts. A password that opens
     * neither slot is a wrong try, and it is [Checked.Refused] too, unless it is the owner's last allowed wrong try: that erases the
     * vault instead, and the result is [Checked.Erased]. Otherwise the result holds the slot the password opened, and the caller closes
     * it. Both slots are tried with the one derivation, so the decoy costs what the real vault costs, and a refused password costs the
     * same as either.
     */
    private suspend fun checkPasswordLocked(password: CharArray): Checked {
        if (!layout.exists()) return Checked.Refused
        val header = VaultHeader.decode(readVaultFile(layout.header))
        val store = failureStore()
        // The Failure Delay check. While the wait runs, the time it has served is saved, so a reboot continues the wait.
        val check = store.checkDelay(clock, header.createdAtWallMs)
        // No copy of the failure state verifies, so the count is unknown and the erase threshold counts as reached. The vault is
        // erased before the password is checked (FailureDelay threat 15). A vault that does not exist returned above, so a fresh
        // install is not erased.
        if (FailureDelay.unverified(check.state)) {
            eraseLocked()
            return Checked.Erased
        }
        // During a delay the password is not checked, so it counts as neither a pass nor a failure. The KDF still runs
        // once, on a fixed input, so a refused attempt takes as long as a real one.
        if (check.blocked) {
            spendKdfTime(header)
            return Checked.Refused
        }
        // The try is counted before the KDF and the verdict, so a try that a restart or an error cuts short still counts (FailureDelay
        // threat 15). A count that cannot be saved gives no verdict: the password is not checked, and the try is refused (threat 11).
        val counted = try {
            store.recordFailure(check.state, clock)
        } catch (e: VaultException) {
            spendKdfTime(header)
            return Checked.Refused
        } catch (e: IOException) {
            spendKdfTime(header)
            return Checked.Refused
        }

        val softKey = withContext(Dispatchers.Default) { deriveSoftKey(password, header.salt, header.kdf) }
        var handedOver = false
        try {
            val contents = VaultKeyFile.decode(readVaultFile(layout.vaultKey))
            val opened = contents.slots.indices.mapNotNull { index ->
                openSoftLayer(softKey, contents, index)?.let { index to it }
            }
            if (opened.size > 1) {
                throw VaultException("The vault key file is damaged: two slots open with one password.")
            }
            if (opened.isEmpty()) {
                // The wrong try is counted already. The owner's last allowed one erases the vault.
                if (FailureDelay.erases(counted)) {
                    eraseLocked()
                    return Checked.Erased
                }
                return Checked.Refused
            }
            // The software layer authenticated, so the password is right. Which vault it opened is known only from the hardware step, so the
            // success is recorded after that step. A real password clears the count. A decoy password keeps the count from before this try,
            // so opening the decoy never lowers the real vault's streak. A right password whose hardware step is cut short keeps its counted
            // try (FailureDelay threat 15).
            val (vaultKey, role) = withHardwareAuth {
                val key = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                val (unwrapped, unwrappedRole) = unwrapOpened(key, opened.single().second)
                Pair(unwrapped, unwrappedRole)
            }
            val success = if (role == VaultRole.DECOY) {
                store.recordDecoySuccess(counted, check.state, clock)
            } else {
                store.recordSuccess(counted, clock)
            }
            handedOver = true
            return Checked.Opened(OpenedSlot(softKey, vaultKey, role, store, success))
        } finally {
            if (!handedOver) Arrays.fill(softKey, 0)
        }
    }

    /**
     * Erases the vault after the owner's last allowed wrong try: the same wipe as [wipe]. A failure is not reported here: the wipe
     * has already tried the keys and every file, and the result is the erased outcome either way.
     */
    private fun eraseLocked() {
        try {
            wipe()
        } catch (e: VaultException) {
            // The wipe went as far as it could. The files are removed even when a key is not.
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
                val softKey = withContext(Dispatchers.Default) { deriveSoftKey(newPassword, header.salt, header.kdf) }
                try {
                    val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                    val real = realSlotOf(contents, vaultKey)
                    val decoy = 1 - real
                    // A new App Password that opens the decoy slot would open both vaults. It is refused as a weak one is.
                    if (openSoftLayer(softKey, contents, decoy) != null) {
                        return CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE)
                    }
                    val (hardwareKey, realSlot) = withHardwareAuth {
                        val key = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                        key to sealSlot(softKey, key, vaultKey, VaultRole.REAL)
                    }
                    commitKeyFile(listOf(realSlot, contents.slots[decoy])) {
                        unwrapsTo(softKey, hardwareKey, realSlot, vaultKey + VaultRole.REAL)
                    }
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

    /**
     * The decoy's entry password is the Duress Password. Changing it re-wraps the decoy slot under the new password, and the
     * decoy's wallets stay as they are. The real slot is kept byte for byte.
     */
    private suspend fun rekeyDecoyLocked(session: VaultSession, newPassword: CharArray): CreateResult {
        try {
            val policy = PasswordPolicy.check(newPassword)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(layout.exists()) { "There is no vault to change." }

            val header = VaultHeader.decode(layout.header.readBytes())
            val decoyKey = session.copyOfVaultKey()
            try {
                val softKey = withContext(Dispatchers.Default) { deriveSoftKey(newPassword, header.salt, header.kdf) }
                try {
                    val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                    val decoy = decoySlotOf(contents, decoyKey)
                    val real = 1 - decoy
                    // The decoy's new password may not open the real slot: one password must never open both vaults.
                    if (openSoftLayer(softKey, contents, real) != null) {
                        return CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE)
                    }
                    val (hardwareKey, decoySlot) = withHardwareAuth {
                        val key = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                        key to sealSlot(softKey, key, decoyKey, VaultRole.DECOY)
                    }
                    commitKeyFile(listOf(contents.slots[real], decoySlot)) {
                        unwrapsTo(softKey, hardwareKey, decoySlot, decoyKey + VaultRole.DECOY)
                    }
                    return CreateResult.Created
                } finally {
                    Arrays.fill(softKey, 0)
                }
            } finally {
                Arrays.fill(decoyKey, 0)
            }
        } finally {
            Arrays.fill(newPassword, '\u0000')
        }
    }

    private suspend fun setDuressLocked(
        session: VaultSession,
        duressPassword: CharArray,
        populate: (VaultSession) -> Unit,
    ): DuressResult {
        try {
            val policy = PasswordPolicy.check(duressPassword)
            if (policy is PolicyResult.Refused) return DuressResult.PolicyRejected(policy.reason)
            check(layout.exists()) { "There is no vault to protect." }

            val header = VaultHeader.decode(layout.header.readBytes())
            val vaultKey = session.copyOfVaultKey()
            val decoyKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            try {
                val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                val real = realSlotOf(contents, vaultKey)
                val softKey = withContext(Dispatchers.Default) { deriveSoftKey(duressPassword, header.salt, header.kdf) }
                try {
                    // The Duress Password may not open the real slot: one password must never open both vaults.
                    if (openSoftLayer(softKey, contents, real) != null) return DuressResult.SameAsAppPassword
                    return installDecoy(vaultKey, decoyKey, softKey, contents, real, populate)
                } finally {
                    Arrays.fill(softKey, 0)
                }
            } catch (e: VaultException) {
                return DuressResult.Failed
            } finally {
                Arrays.fill(vaultKey, 0)
                Arrays.fill(decoyKey, 0)
            }
        } finally {
            Arrays.fill(duressPassword, '\u0000')
        }
    }

    /**
     * On a decoy session, the Duress Password is the decoy's own entry password. Setting it is a change of that password, which
     * keeps the decoy's wallets. Nothing outside the decoy changes, so the decoy shows the same result as the real vault does.
     */
    private suspend fun setDuressDecoyLocked(session: VaultSession, duressPassword: CharArray): DuressResult {
        if (session.listWallets().isEmpty()) {
            Arrays.fill(duressPassword, '\u0000')
            return DuressResult.DecoyEmpty
        }
        return when (val result = rekeyDecoyLocked(session, duressPassword)) {
            CreateResult.Created -> DuressResult.Enabled
            is CreateResult.PolicyRejected -> DuressResult.PolicyRejected(result.reason)
            CreateResult.DeviceUnsupported, CreateResult.BackupAllowed -> DuressResult.Failed
        }
    }

    /**
     * Makes the new decoy with [populate], then commits its slot in place of the old one. Nothing is committed unless the
     * decoy holds a wallet, and a failure deletes the new decoy's files and nothing else. After a commit, the old decoy's
     * files are purged, since no password opens them any longer.
     */
    private suspend fun installDecoy(
        vaultKey: ByteArray,
        decoyKey: ByteArray,
        softKey: ByteArray,
        contents: VaultKeyFile.Contents,
        real: Int,
        populate: (VaultSession) -> Unit,
    ): DuressResult {
        // The new decoy starts with unused slots lent from the real vault, so populating it does not grow the store.
        withPadOf(vaultKey, REAL_SECTION) { real -> withPadOf(decoyKey, DECOY_SECTION) { real.lend(it, WALLET_SLOT_STEP) } }
        val decoy = VaultSession(layout, keys, decoyKey.copyOf(), isDecoy = true, pad = WalletPad(layout, keys, decoyKey, DECOY_SECTION))
        try {
            populate(decoy)
            if (decoy.listWallets().isEmpty()) {
                withRealPad(vaultKey) { discard(decoyKey, it) }
                return DuressResult.DecoyEmpty
            }
        } catch (e: Exception) {
            withRealPad(vaultKey) { discard(decoyKey, it) }
            return DuressResult.Failed
        } finally {
            decoy.close()
        }

        val committed = try {
            val (hardwareKey, decoySlot) = withHardwareAuth {
                val key = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                key to sealSlot(softKey, key, decoyKey, VaultRole.DECOY)
            }
            commitKeyFile(listOf(contents.slots[real], decoySlot)) {
                unwrapsTo(softKey, hardwareKey, decoySlot, decoyKey + VaultRole.DECOY)
            }
            true
        } catch (e: VaultException) {
            false
        } catch (e: IOException) {
            false
        }
        if (!committed) {
            withRealPad(vaultKey) { discard(decoyKey, it) }
            return DuressResult.Failed
        }
        // The new decoy is committed, so a purge that fails leaves the old decoy's files for a later purge, and the result stays Enabled.
        bestEffort { purgeUnowned(listOf(vaultKey, decoyKey)) }
        return DuressResult.Enabled
    }

    private fun removeDuressLocked(session: VaultSession) {
        check(layout.exists()) { "There is no vault to change." }
        val vaultKey = session.copyOfVaultKey()
        try {
            val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
            val real = realSlotOf(contents, vaultKey)
            // The decoy slot becomes random bytes first, so the Duress Password opens nothing from this write on. Its files
            // are purged after that, so a failure before the write leaves the decoy as it was.
            try {
                commitKeyFile(listOf(contents.slots[real], VaultKeyFile.randomSlot(random))) { true }
            } catch (e: IOException) {
                throw VaultException("The vault key file could not be changed.", e)
            }
            // The slot is already replaced, so a purge that fails leaves the old decoy's files for a later purge.
            bestEffort { purgeUnowned(listOf(vaultKey)) }
        } finally {
            Arrays.fill(vaultKey, 0)
        }
    }

    /**
     * A decoy session removes only its own decoy: its wallets and records are deleted. With [keepEmptyDecoy] the slot stays
     * under the Duress Password, so that password opens a fresh empty decoy. Otherwise the slot goes random, so it opens
     * nothing.
     */
    private fun removeOwnDecoyLocked(session: VaultSession, keepEmptyDecoy: Boolean) {
        check(layout.exists()) { "There is no vault to change." }
        val decoyKey = session.copyOfVaultKey()
        try {
            val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
            val decoy = decoySlotOf(contents, decoyKey)
            // Keeping the slot leaves the Duress Password working, and only the decoy's files go. A wipe looks like a real
            // wipe, but unlocking with the same password lands in an empty vault rather than a refusal that names a decoy.
            val decoySlot = if (keepEmptyDecoy) contents.slots[decoy] else VaultKeyFile.randomSlot(random)
            try {
                commitKeyFile(listOf(contents.slots[1 - decoy], decoySlot)) { true }
            } catch (e: IOException) {
                throw VaultException("The vault key file could not be changed.", e)
            }
            // Only the files this key owns go. The real vault's wallets and records are not owned by this key.
            withPadOf(decoyKey, DECOY_SECTION) { discard(decoyKey, it, keepListing = true) }
            dropDecoyPhotos(layout)
        } finally {
            Arrays.fill(decoyKey, 0)
        }
    }

    /** One timed calibration derivation. Runs on [Dispatchers.Default]. */
    private fun calibrateKdf(): KdfParams = KdfCalibration.calibrate { params ->
        val probePassword = CALIBRATION_PASSWORD.toCharArray()
        val salt = ByteArray(VaultHeader.SALT_BYTES).also { random.nextBytes(it) }
        val started = System.nanoTime()
        val out = deriveSoftKey(probePassword, salt, params)
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
            val out = withContext(Dispatchers.Default) { deriveSoftKey(fixedInput, header.salt, header.kdf) }
            Arrays.fill(out, 0)
        } finally {
            Arrays.fill(fixedInput, '\u0000')
        }
    }

    /**
     * The hardware-wrapped blob of slot [index] if [softKey] opens its software layer, or null. The attempt costs the same
     * whether or not it succeeds, and it never reports which slot a password is for.
     */
    private fun openSoftLayer(softKey: ByteArray, contents: VaultKeyFile.Contents, index: Int): ByteArray? = try {
        Aead.open(softKey, VaultKeyFile.SOFTWARE_AAD, contents.sealedOf(index))
    } catch (e: AEADBadTagException) {
        null
    }

    /** The Vault Key and role inside a hardware-wrapped blob. The caller zeroes the key. */
    private fun unwrapOpened(hardwareKey: SecretKey, hardwareWrapped: ByteArray): Pair<ByteArray, Byte> {
        val plain = keystore(HARDWARE_FAILURE) { Aead.openWithKeystore(hardwareKey, VaultKeyFile.HARDWARE_AAD, hardwareWrapped) }
        try {
            if (plain.size != KEY_BYTES + 1) throw VaultException("The vault key is damaged.")
            val role = plain[KEY_BYTES]
            if (role != VaultRole.REAL && role != VaultRole.DECOY) throw VaultException("The vault key is damaged.")
            return plain.copyOf(KEY_BYTES) to role
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    /** True when [slot] opens under [softKey] and the hardware key to exactly [expected]. */
    private fun unwrapsTo(softKey: ByteArray, hardwareKey: SecretKey, slot: ByteArray, expected: ByteArray): Boolean {
        return try {
            val hardwareWrapped = Aead.open(softKey, VaultKeyFile.SOFTWARE_AAD, VaultKeyFile.sealedPartOf(slot))
            val plain = Aead.openWithKeystore(hardwareKey, VaultKeyFile.HARDWARE_AAD, hardwareWrapped)
            try {
                MessageDigest.isEqual(plain, expected)
            } finally {
                Arrays.fill(plain, 0)
            }
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: ProviderException) {
            false
        }
    }

    /** The slot for [role]: [vaultKey] and the role, sealed under both layers, in a slot that names the vault. */
    private fun sealSlot(softKey: ByteArray, hardwareKey: SecretKey, vaultKey: ByteArray, role: Byte): ByteArray {
        val plain = vaultKey + role
        try {
            val hardwareWrapped = keystore(HARDWARE_FAILURE) {
                Aead.sealWithKeystore(hardwareKey, VaultKeyFile.HARDWARE_AAD, plain)
            }
            return VaultKeyFile.slotOf(vaultKey, Aead.seal(softKey, VaultKeyFile.SOFTWARE_AAD, hardwareWrapped), random)
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    /**
     * Writes [slots] as the new `vault.key`, after reading it back. The file is committed only if every slot in [slots] is in it
     * byte for byte and [verified] holds. Otherwise the temp file is removed and nothing changes.
     */
    private fun commitKeyFile(slots: List<ByteArray>, verified: () -> Boolean) {
        val temp = writeTemp(layout.vaultKey, VaultKeyFile.encode(slots, random))
        val intact = try {
            val contents = VaultKeyFile.decode(temp.readBytes())
            slots.all { wanted -> contents.slots.any { it.contentEquals(wanted) } } && verified()
        } catch (e: VaultException) {
            false
        } catch (e: IOException) {
            false
        }
        if (!intact) {
            temp.delete()
            throw VaultException("The new vault key file did not verify, so the vault was not changed.")
        }
        commitTemp(temp, layout.vaultKey)
    }

    /**
     * The software key of a slot: `K_sw = HKDF-SHA256(Argon2id(password, salt) || HMAC(slot key, salt), info = SLOT_INFO)`. The HMAC runs
     * in the device's Keystore, so each guess needs this device. The call blocks, so a caller runs it on [Dispatchers.Default]. The caller
     * zeroes the result.
     */
    private fun deriveSoftKey(password: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        val stretched = Argon2Kdf.derive(password, salt, kdf)
        try {
            val bound = keystore(HARDWARE_FAILURE) { keys.slotMac(salt) }
            val ikm = stretched + bound
            try {
                return hkdfSha256(ikm, salt = null, info = SLOT_INFO, length = KEY_BYTES)
            } finally {
                Arrays.fill(bound, 0)
                Arrays.fill(ikm, 0)
            }
        } finally {
            Arrays.fill(stretched, 0)
        }
    }

    /** The index of the slot that holds the real vault. A file that names no slot for [vaultKey] is damaged. */
    private fun realSlotOf(contents: VaultKeyFile.Contents, vaultKey: ByteArray): Int {
        val index = VaultKeyFile.ownerIndex(contents, vaultKey)
        if (index < 0) throw VaultException("The vault key file does not hold this vault's key.")
        return index
    }

    /** The index of the slot that holds the decoy vault for [decoyKey]. A file that names no slot for it is damaged. */
    private fun decoySlotOf(contents: VaultKeyFile.Contents, decoyKey: ByteArray): Int {
        val index = VaultKeyFile.ownerIndex(contents, decoyKey)
        if (index < 0) throw VaultException("The vault key file does not hold this decoy's key.")
        return index
    }

    /**
     * Deletes the wallets and vault records that [key] owns. This drops a decoy that was being set up, or wipes the decoy. A kept
     * listing ([keepListing]: a decoy emptied but kept) frees its Review Record slots and folders through [pad], for its own reuse.
     * A dropped decoy's pool files are overwritten, and its folders go to [pad] as unused slots.
     */
    private fun discard(key: ByteArray, pad: WalletPad, keepListing: Boolean = false) {
        val owned = layout.wallets.list()?.filter { ownsWallet(keys, layout, key, it) }.orEmpty()
        val pool = ReviewPool(layout.reviewPool, key)
        if (keepListing) {
            owned.forEach { pool.release(it) }
        } else {
            pool.ownedNames().forEach { overwriteInPlace(File(layout.reviewPool, it)) }
        }
        // The folders stay, emptied and refilled with random bytes, so the store's folder count never falls.
        pad.release(owned)
        VaultRecordNames.KNOWN.forEach { name ->
            val file = File(layout.vaultRecords, VaultRecordNames.fileName(key, name))
            if (!file.isFile) return@forEach
            // A wipe keeps the name and size of the record file, so the store's file shape does not change.
            if (keepListing) overwriteWithRandom(file) else file.delete()
        }
    }

    /**
     * Deletes what neither key in [keep] owns: wallet folders that hold a key file, vault record files, and pool files. A folder
     * without a key file is still being written, so it is left alone. This is how a dropped Decoy Vault's files go. A padded store
     * keeps each dropped folder as an unused slot, so the folder count stays the same, and the real vault's pad takes them over.
     * A pool file that no kept key owns is overwritten and kept, so the pool keeps its size. The first key in [keep] is the real vault's.
     */
    private fun purgeUnowned(keep: List<ByteArray>) {
        withRealPad(keep.first()) { pad ->
            val dropped = layout.wallets.list()?.filter { id ->
                val dir = layout.walletDir(id)
                File(dir, WALLET_KEY_FILE_NAME).isFile && keep.none { ownsWallet(keys, layout, it, id) }
            }.orEmpty()
            pad.release(dropped)
        }
        val kept = keep.flatMap { key -> VaultRecordNames.KNOWN.map { VaultRecordNames.fileName(key, it) } }.toSet()
        layout.vaultRecords.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") && file.name !in kept) file.delete()
        }
        ReviewPool.shredExcept(layout.reviewPool, keep.flatMap { ReviewPool(layout.reviewPool, it).ownedNames() }.toSet())
        // The Decoy Vault's photos are cleared too. A dropped decoy takes them with it, and a new decoy starts with none.
        dropDecoyPhotos(layout)
    }

    /** The real vault's pad for the duration of [block]. Its key is zeroed afterwards. */
    private inline fun <T> withRealPad(vaultKey: ByteArray, block: (WalletPad) -> T): T = withPadOf(vaultKey, REAL_SECTION, block)

    /** The pad of the vault whose key is [vaultKey], section [section], for the duration of [block]. Its key is zeroed afterwards. */
    private inline fun <T> withPadOf(vaultKey: ByteArray, section: Int, block: (WalletPad) -> T): T {
        val pad = WalletPad(layout, keys, vaultKey, section)
        try {
            return block(pad)
        } finally {
            pad.close()
        }
    }

    /** Writes random bytes over [file], keeping its length. Used when a wipe must not change the store's file shape. */
    private fun overwriteWithRandom(file: File) {
        val size = file.length().toInt()
        if (size <= 0) return
        file.writeBytes(ByteArray(size).also { random.nextBytes(it) })
    }

    private fun failureStore(): FailureStore = FailureStore(layout.root, keys, readBootCount)

    /** The bytes of a vault file. A file that cannot be read is damage to the vault, reported as the documented [VaultException]. */
    private fun readVaultFile(file: File): ByteArray = try {
        file.readBytes()
    } catch (e: IOException) {
        throw VaultException("The vault file ${file.name} could not be read.", e)
    }

    /**
     * Starts the Failure Delay state. Its first Keystore key is made here, so a device whose Keystore refuses it gets no vault.
     * Returns false in that case, and also when the first state cannot be saved.
     */
    private fun createFailureState(): Boolean = try {
        failureStore().initialize(clock)
        true
    } catch (e: VaultException) {
        false
    } catch (e: IOException) {
        false
    }

    private companion object {
        const val KEY_BYTES = VaultKeyFile.KEY_BYTES
        const val UNKNOWN_SECURITY_LEVEL = -1
        const val HARDWARE_FAILURE = "The vault's hardware key cannot unwrap the vault key."
        const val CALIBRATION_PASSWORD = "lensly calibration"
        val SLOT_INFO = "lensly-vault-slot-v$VAULT_FORMAT_VERSION".toByteArray(Charsets.US_ASCII)

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
            throw keystoreFailure(message, e)
        } catch (e: ProviderException) {
            throw VaultException(message, e)
        }
    }
}

/**
 * `Settings.Global.BOOT_COUNT`, or null when the device does not report it. The count goes up by one at each reboot, so the
 * Failure Delay can tell a reboot, where the monotonic clock restarted, from a wall clock that moved. A root user can edit the
 * count; a count that does not match the one recorded with the state only continues the wait from boot, so the edit cannot
 * shorten it.
 */
private fun bootCountOf(context: Context): Int? = try {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
} catch (e: Settings.SettingNotFoundException) {
    null
} catch (e: SecurityException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/**
 * True on an emulator, through the same check the secure-window rule uses. JVM tests have no device values, so a
 * missing one counts as a real device.
 */
internal fun deviceIsEmulator(): Boolean {
    val hardware = Build.HARDWARE ?: return false
    val product = Build.PRODUCT ?: return false
    val fingerprint = Build.FINGERPRINT ?: return false
    return isEmulator(hardware, product, fingerprint)
}

/**
 * Deletes a vault: its Keystore keys first (through [deleteKeys]), then every file under its root and its photos folder, each overwritten
 * and synced before it is deleted. Without the keys the wrapped vault key and every wallet key are unrecoverable even if some file survives.
 * The files go even when [deleteKeys] throws, so a key that cannot be deleted never leaves the vault on disk.
 *
 * @throws VaultException if some file could not be removed, or if [deleteKeys] threw.
 */
fun wipeVault(layout: VaultLayout, deleteKeys: () -> Unit) {
    try {
        deleteKeys()
    } finally {
        // Each file is overwritten before it is deleted, so no record, key file, pool slot or photo survives in the storage.
        overwriteFiles(layout.root)
        overwriteFiles(layout.photos)
        FailureStore.forget(layout.root)
        val rootGone = layout.root.deleteRecursively()
        val photosGone = layout.photos.deleteRecursively()
        if (!rootGone || !photosGone) throw VaultException("Some vault files could not be deleted.")
    }
}

/** Overwrites every file under [dir] in place. A file that cannot be overwritten is still deleted by the caller. */
private fun overwriteFiles(dir: File) {
    dir.walkTopDown().filter { it.isFile }.forEach { file ->
        try {
            overwriteInPlace(file)
        } catch (e: IOException) {
            // Deleted by the caller all the same.
        }
    }
}

/** Overwrites the files in [dir], then deletes the folder. A Decoy Vault's photos go this way when the decoy goes. */
private fun shredFolder(dir: File) {
    overwriteFiles(dir)
    dir.deleteRecursively()
}

/**
 * Removes a dropped Decoy Vault's photos, and puts placeholder bytes back in its key file, so the photo folder lists the same files
 * whether or not a decoy exists. A key file that cannot be written keeps its old bytes, which reveal nothing without the decoy's password.
 */
private fun dropDecoyPhotos(layout: VaultLayout) {
    shredFolder(layout.photoDir(decoy = true))
    try {
        writeAtomically(layout.photoKeyFile(decoy = true), placeholderPhotoKey())
    } catch (e: IOException) {
        // The next unlock of the decoy would replace it anyway; the drop itself must not fail half way.
    }
}
