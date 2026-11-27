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
import kotlin.random.asKotlinRandom
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

    /**
     * A Decoy Vault's password change was not run: a Failure Delay is running, its try could not be counted, or the try was the last
     * allowed one and the vault erased itself. Nothing was written. The caller locks, as at the gate.
     */
    data object Locked : CreateResult
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

/** Outcome of [Vault.setDuress]. Only [Enabled] changes the real vault, and [Failed] erases it when the undo fails too. Others leave it as it was. */
sealed interface DuressResult {
    /** The Duress Password is set. It opens a Decoy Vault that holds at least one wallet. */
    data object Enabled : DuressResult

    /** The Duress Password failed [PasswordPolicy]. */
    data class PolicyRejected(val reason: PolicyReason) : DuressResult

    /** The Duress Password is the App Password. One password must never open both vaults. */
    data object SameAsAppPassword : DuressResult

    /** The populate step left the Decoy Vault without a wallet, so the Duress Password was not enabled. */
    data object DecoyEmpty : DuressResult

    /**
     * The new vault key file could not be written and read back, or the decoy could not be made. When the undo of a half-made decoy fails
     * too, the vault is erased, and the next gate finds no vault.
     */
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
 * `unlock.bin` is in another format than this build reads. It cannot be verified, so an unlock erases the vault, and nothing is migrated: the
 * owner restores the wallets from their seed phrases into a new vault.
 */
class VaultFormatChangedException : VaultException(VAULT_FORMAT_CHANGED)

/**
 * The App Password vault, and its optional Duress Password.
 *
 * The Vault Key is protected by three keys, and all are needed to recover it:
 * 1. Slot: a non-exportable Keystore HMAC-SHA256 key (`k2`) binds every guess to this device.
 * 2. Software: `K_sw = HKDF-SHA256(Argon2id(password, salt) || HMAC(slot key, Argon2id(password, salt)), info = "lensly-vault-slot-v5")`,
 *    used with AES-256-GCM to wrap the hardware blob. The Keystore sees the stretched password, so each guess needs the full Argon2
 *    work and this device's slot key, and nothing stored lets a copy of the vault files be tested without both.
 * 3. Hardware: a non-exportable Keystore AES-256-GCM key (`k1`) wraps the Vault Key.
 *
 * `unlock.bin` holds two slots: one for the real vault and one for the Decoy Vault that a Duress Password opens. Its size
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
 * `unlock.bin` is bound to the Failure Delay state, which holds its SHA-256 and is updated by every write of the file (see
 * [FailureState.keyFileDigest]). An older copy put back no longer matches, so it is unverifiable and the vault is erased.
 *
 * The header (`header.bin`) is MACed under the header key. A header that does not verify is unverifiable, so [unlock] erases the vault
 * before it checks any password, as it does for an unverifiable failure state (see [VaultHeader]).
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
    /**
     * Runs after the vault erases itself, on the owner's last allowed wrong try or on an unverifiable file (see [unlock]). An explicit [wipe]
     * does not run it, since its caller already knows the vault is gone.
     */
    private val onErased: () -> Unit = {},
) {
    constructor(context: Context, clock: Clock = RealClock, onErased: () -> Unit = {}) : this(context, clock, null, onErased)

    constructor(context: Context, clock: Clock, kdfOverride: KdfParams?, onErased: () -> Unit = {}) :
        this(context, clock, kdfOverride, hardwareKeyUserAuth = true, onErased = onErased)

    /**
     * [hardwareKeyUserAuth] is false only for [app.lensly.core.vault.instrumentedVault]. Android refuses to make a key that needs a
     * screen lock on a device without one, and the emulators that run the instrumented tests have none. The app passes true.
     */
    internal constructor(
        context: Context,
        clock: Clock,
        kdfOverride: KdfParams?,
        hardwareKeyUserAuth: Boolean,
        onErased: () -> Unit = {},
    ) : this(
        layout = VaultLayout.of(context),
        keys = AndroidKeystoreBackend(context.applicationContext, hardwareKeyUserAuth),
        clock = clock,
        kdfOverride = kdfOverride,
        readBootCount = { bootCountOf(context.applicationContext) },
        backupAllowed = BackupExclusion.allowsBackup(context.applicationInfo.flags),
        authorize = { confirmDeviceCredential(context.applicationContext) },
        onErased = onErased,
    )

    private val mutex = Mutex()
    private val random = SecureRandom()

    /** True once a vault has been created and not wiped. An erase under way counts as a wipe (see [VaultLayout.exists]). */
    fun exists(): Boolean = layout.exists()

    /**
     * True when vault files are left without a whole vault, or an erase stopped half way. The gate then shows [VAULT_DAMAGED], and [create]
     * refuses. [finishPendingWipe] runs first at the gate, so only an erase that fails again reaches it.
     */
    fun isPartial(): Boolean = layout.isPartial()

    /** True before the first setup, when there is no vault and no vault file. See [VaultLayout.isBeforeSetup]. */
    fun isBeforeSetup(): Boolean = layout.isBeforeSetup()

    /**
     * True when this device may create a vault, under [KeystorePolicy], and this build may create one, which it may not when it allows backup.
     * The answer comes from the same probe as [deviceSecurity], so it is meaningful only while no vault exists.
     */
    fun canCreate(): Boolean =
        !backupAllowed &&
            KeystorePolicy.verdict(deviceSecurity().hardwareBacked, BuildConfig.DEBUG, deviceIsEmulator()) !=
            KeystoreVerdict.Refused

    /**
     * Why this build may not create a vault, or null when it may. A build whose packaged manifest allows backup is refused, because
     * its vault could leave the device. The text names [appName], and holds no secret (see [BackupExclusion.refusal]).
     */
    fun refusal(appName: String): String? = if (backupAllowed) BackupExclusion.refusal(appName) else null

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
     * checked, so no try is counted. An open clears the count of wrong passwords, as the right App Password does, so wrong tries made
     * while the owner only uses biometric unlock do not pile up toward the erase. The session takes its own copy of [vaultKey], and the
     * caller zeroes its own.
     */
    suspend fun unlockWithVaultKey(vaultKey: ByteArray): VaultSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Biometric tries do not count toward the failure delay or erase count: the OS BIOMETRIC_STRONG lockout already limits them.
                if (!layout.exists()) return@withLock null
                // An unverifiable header is erased before the key is looked at, as it is before a password is checked.
                if (verifiedHeader() == null) {
                    eraseLocked()
                    return@withLock null
                }
                val store = failureStore()
                // A state that cannot be read is left for the next password try too, so the key file is checked without its digest.
                val state = try {
                    store.load(clock)
                } catch (e: VaultException) {
                    null
                } catch (e: IOException) {
                    null
                }
                val contents = verifiedKeyFile(state) ?: run {
                    eraseLocked()
                    return@withLock null
                }
                if (VaultKeyFile.ownerIndex(contents, vaultKey) < 0) return@withLock null
                // A state that does not verify is left for the next password try, which erases the vault (FailureDelay threat 15).
                bestEffort {
                    if (state != null && state.failures > 0 && !FailureDelay.unverified(state)) store.recordSuccess(state, clock)
                }
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
     * after a right password until the next wrong try. A caller that shows another erase count than the stored one, as a Decoy Vault
     * does, passes it as [shownEraseAfter], and the tries are then counted against the lower of it and the stored one, so the warning
     * never comes after the real erase. The vault itself always erases at the stored count: a shown count above it warns early, and one
     * below it never erases at the shown count.
     */
    suspend fun triesBeforeErase(shownEraseAfter: Int? = null): Int? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!layout.exists()) return@withLock null
                val state = failureStore().load(clock)
                FailureDelay.triesLeftToWarn(
                    if (shownEraseAfter == null) state else state.copy(wipeAfter = minOf(shownEraseAfter, state.wipeAfter)),
                )
            }
        }

    /**
     * The Failure Delay still to run, in milliseconds, or 0 when none runs. It only reads the stored state: it counts no try, serves no
     * wait, saves nothing and moves no counter, so it can be asked any number of times. Only [unlock] checks the password and enforces the
     * delay.
     */
    suspend fun waitRemainingMs(): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!layout.exists()) return@withLock 0L
                val header = try {
                    verifiedHeader()
                } catch (e: VaultException) {
                    null
                } ?: return@withLock 0L
                failureStore().remainingWaitMs(clock, header.createdAtWallMs)
            }
        }

    /**
     * Replaces the App Password of an unlocked vault. Only the vault's own slot in `unlock.bin` changes, so wallet data is
     * untouched, and the other slot is kept byte for byte. The new file is written to a temp file, read back and checked,
     * and only then renamed into place.
     *
     * On the real vault, the new password may not open the decoy slot. It is refused with the reason of a weak password.
     *
     * On a decoy session, which keeps its own entry password, every change is one counted try in the real vault's failure state, with
     * the Failure Delay and the erase count of a wrong password at the gate, and the try is never given back. A new password that opens the real slot is
     * not written, and the result is [CreateResult.Created] all the same, so the answer never says whether the real password was
     * hit. A delay, an uncountable try or the erase gives [CreateResult.Locked].
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
     * On a decoy session the call changes nothing and answers [DuressResult.Enabled], so the decoy answers as the real vault does.
     * The decoy's [populate] is not run.
     */
    suspend fun setDuress(
        session: VaultSession,
        duressPassword: CharArray,
        populate: (VaultSession) -> Unit,
    ): DuressResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (session.isDecoy) setDuressDecoyLocked(duressPassword) else setDuressLocked(session, duressPassword, populate)
            }
        }

    /**
     * Removes the Duress Password. The decoy slot becomes random bytes again, so the Duress Password opens nothing from then on,
     * and the Decoy Vault's wallets and records are deleted. The real vault's files are never touched. Without a Duress
     * Password this changes nothing visible, and the vault cannot tell the difference, so the owner may call it either way.
     * On a decoy session it changes nothing, since a decoy holds no Duress Password of its own, and the call returns as the real vault's does.
     *
     * @throws VaultException if the new vault key file cannot be written, in which case nothing changed.
     */
    suspend fun removeDuress(session: VaultSession): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!session.isDecoy) removeDuressLocked(session)
            }
        }

    /**
     * Deletes the vault: its Keystore keys first, then every file, the real vault and the decoy vault alike. Call this on an
     * explicit user command, or from the erase that follows the owner's last allowed wrong try (see [unlock]). Do not call it
     * while any other operation of this class is running. If it throws after its marker was written, [finishPendingWipe] completes the
     * erase at the next gate.
     */
    fun wipe() {
        wipeVault(layout) { keys.deleteAll() }
    }

    /**
     * Finishes an erase that stopped half way, which [wipeVault] left a marker for, and erases vault files that are left without a whole
     * vault (see [isPartial]): they cannot be verified, so they go like any other unverifiable state. Then it runs [onErased], so the stores
     * outside the vault go even when the process died before its caller erased them. The gate calls it before it looks at the vault, so a
     * vault that a reset has started to erase is never unlocked. Returns false while vault files are still there, when some file could not
     * be removed: the vault then stays hidden, and the next gate tries again.
     */
    suspend fun finishPendingWipe(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (layout.isPartial()) eraseLocked()
                !layout.isPartial()
            }
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
        removeOwnDecoyLocked(session)
    }

    /**
     * The Keystore capabilities recorded when the vault was created. Without a vault, the device is probed. A header that does not verify
     * throws [VaultException], because the recorded capabilities cannot be trusted.
     */
    fun deviceSecurity(): DeviceSecurity {
        if (layout.exists()) {
            val header = verifiedHeaderOrThrow()
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
            // Every guess runs through the slot key, and the header is MACed under the header key, so no vault is made without both.
            if (keystoreOrNull { keys.createSlotKey() } == null || keystoreOrNull { keys.createHeaderKey() } == null) {
                keystoreOrNull { keys.deleteAll() }
                return CreateResult.DeviceUnsupported
            }

            val salt = ByteArray(VaultHeader.SALT_BYTES).also { random.nextBytes(it) }
            val vaultKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            try {
                val kdf = kdfOverride ?: withContext(Dispatchers.Default) { calibrateKdf() }
                val softKey = withContext(Dispatchers.Default) { deriveStoredKey(password, salt, kdf) }
                try {
                    val realSlot = withHardwareAuth {
                        sealSlot(softKey, keystore(HARDWARE_FAILURE) { keys.hardwareKey() }, vaultKey, VaultRole.REAL)
                    }
                    val decoySlot = VaultKeyFile.randomSlot(random)
                    val keyFile = VaultKeyFile.encode(listOf(realSlot, decoySlot), random)
                    layout.root.mkdirs()
                    // The files of this create all sit under the vault root, from the first one written on. A failure after that removes
                    // the root, so a failed create leaves no partial vault: the vault is as it was before the call.
                    var committed = false
                    try {
                        if (!createFailureState(VaultKeyFile.digestOf(keyFile))) {
                            // The hardware key was made a moment ago, and the directory may hold the first Delay State copies.
                            // Both go, so a refused creation leaves nothing behind. The directory is removed in the finally below.
                            keystoreOrNull { keys.deleteAll() }
                            return CreateResult.DeviceUnsupported
                        }
                        writeAtomically(layout.vaultKey, keyFile)
                        // The record folder starts with its full set of files, so a Decoy Vault never adds one.
                        writeRecordPlaceholders(vaultKey)
                        // The store starts with its full set of wallet folders, so it never shows an empty one.
                        withRealPad(vaultKey) { it.padTo(WALLET_SLOT_STEP) }
                        // The Review Record pool starts with its first batch, so an empty vault shows the same pool as one with records.
                        ReviewPool(layout.reviewPool, vaultKey).use { it.ensureFirstBatch() }
                        // The header goes last. Its presence is what makes exists() true, so a crash earlier leaves no vault.
                        val header = VaultHeader(
                            kdf = kdf,
                            salt = salt,
                            createdAtWallMs = clock.wallMillis(),
                            strongBox = keys.strongBox(),
                            hardwareBacked = VaultKeystore.isHardwareBacked(securityLevel),
                            keySecurityLevel = securityLevel,
                        )
                        writeAtomically(layout.header, header.encodeFile { input -> headerMacOf(input) })
                        committed = true
                    } finally {
                        if (!committed) {
                            // The Keystore keys made here stay until the next create replaces them. deleteAll would also remove the pending
                            // photo key, which opens the photos taken before setup.
                            // Each file is overwritten first, as in a wipe, so the wrapped vault key does not survive in the storage.
                            overwriteFiles(layout.root)
                            FailureStore.forget(layout.root)
                            layout.root.deleteRecursively()
                        }
                    }
                    // The photo key is made now, so the cover can seal its first photo before anyone unlocks the vault.
                    bestEffort {
                        val session = VaultSession(
                            layout,
                            keys,
                            vaultKey,
                            pad = WalletPad(layout, keys, vaultKey, REAL_SECTION),
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
        val nfc = PasswordForm.nfcOrNull(password)
        try {
            return checkPasswordLocked(password, nfc)
        } finally {
            nfc?.fill('\u0000')
        }
    }

    /**
     * The check of [checkPasswordLocked], with [nfc], the NFC form of [password], or null when [password] is NFC already. A password that
     * is not NFC derives two keys, the typed text's and its NFC form's, always both and in that order, so the cost depends on the form
     * typed and never on the outcome: a real, a decoy and a wrong password cost the same, and the try is counted once. The typed text's key
     * wins when both open a slot, so a vault made from the raw text keeps opening. A vault made now holds the NFC form.
     */
    private suspend fun checkPasswordLocked(password: CharArray, nfc: CharArray?): Checked {
        if (!layout.exists()) return Checked.Refused
        // A header that does not verify is unverifiable, so the vault is erased before the password is checked, as an unverifiable failure
        // state is (see [FailureDelay]). A header of another format version does not verify either (see [VaultHeader.decodeFile]).
        val header = verifiedHeader() ?: run {
            eraseLocked()
            return Checked.Erased
        }
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
        // A key file that is not the one last written, or that cannot be read as a key file, is unverifiable too (see [verifiedKeyFile]).
        val contents = verifiedKeyFile(check.state) ?: run {
            eraseLocked()
            return Checked.Erased
        }
        // During a delay the password is not checked, so it counts as neither a pass nor a failure. The KDF still runs
        // once, on a fixed input, so a refused attempt takes as long as a real one.
        val derivations = if (nfc == null) 1 else 2
        if (check.blocked) return refusedAfterSpendingKdfTime(header, derivations)
        // The try is counted before the KDF and the verdict, so a try that a restart or an error cuts short still counts (FailureDelay
        // threat 15). A count that cannot be saved gives no verdict: the password is not checked, and the try is refused (threat 11).
        val counted = try {
            store.recordFailure(check.state, clock)
        } catch (e: VaultException) {
            return refusedAfterSpendingKdfTime(header, derivations)
        } catch (e: IOException) {
            return refusedAfterSpendingKdfTime(header, derivations)
        }

        // A slot key that is gone for good, as a hardware key is, leaves a vault that no password opens: it is erased.
        val typedKey = try {
            withContext(Dispatchers.Default) { deriveSoftKey(password, header.salt, header.kdf) }
        } catch (e: VaultKeyInvalidatedException) {
            eraseLocked()
            return Checked.Erased
        }
        var nfcKey: ByteArray? = null
        var handedKey: ByteArray? = null
        try {
            nfcKey = nfc?.let { form -> withContext(Dispatchers.Default) { deriveSoftKey(form, header.salt, header.kdf) } }
            val typedOpened = slotsOpenedBy(typedKey, contents)
            val nfcOpened = if (nfcKey == null) emptyList() else slotsOpenedBy(nfcKey, contents)
            val useNfc = typedOpened.isEmpty() && nfcOpened.isNotEmpty()
            val softKey = if (useNfc) nfcKey!! else typedKey
            val opened = if (useNfc) nfcOpened else typedOpened
            if (opened.size > 1) {
                // Two slots opening under one password is a damaged file, which is unverifiable, so the vault is erased.
                eraseLocked()
                return Checked.Erased
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
            // A hardware key that is gone for good, or a slot that holds no vault key and role, cannot be repaired: the vault is erased.
            val unwrapped = withHardwareAuth {
                val key = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                unwrapOpened(key, opened.single().second)
            }
            if (unwrapped == null) {
                eraseLocked()
                return Checked.Erased
            }
            val (vaultKey, role) = unwrapped
            val success = if (role == VaultRole.DECOY) {
                store.recordDecoySuccess(counted, check.state, clock)
            } else {
                store.recordSuccess(counted, clock)
            }
            handedKey = softKey
            return Checked.Opened(OpenedSlot(softKey, vaultKey, role, store, success))
        } catch (e: VaultKeyInvalidatedException) {
            // A key that is gone for good, whichever step finds it, leaves a vault that no password opens.
            eraseLocked()
            return Checked.Erased
        } finally {
            if (handedKey !== typedKey) Arrays.fill(typedKey, 0)
            if (nfcKey != null && handedKey !== nfcKey) Arrays.fill(nfcKey, 0)
        }
    }

    /** The slots [softKey] opens, as their index and hardware-wrapped blob. One key opens at most one. */
    private fun slotsOpenedBy(softKey: ByteArray, contents: VaultKeyFile.Contents): List<Pair<Int, ByteArray>> =
        contents.slots.indices.mapNotNull { index -> openSoftLayer(softKey, contents, index)?.let { index to it } }

    /**
     * Erases the vault after the owner's last allowed wrong try: the same wipe as [wipe], then [onErased], so the app can remove what the
     * wipe does not reach. A failure is not reported here: the wipe has already tried the keys and every file, even when its marker could
     * not be written, and the result is the erased outcome either way. A file that could not be deleted, such as the plain recording, is not
     * kept silently: the marker stays, so the next gate finishes the erase (see [finishPendingWipe]), and the app start shreds the recording.
     */
    private fun eraseLocked() {
        try {
            wipe()
        } catch (e: VaultException) {
            // The wipe went as far as it could. The files are removed even when a key is not.
        }
        onErased()
    }

    private suspend fun rekeyLocked(session: VaultSession, newPassword: CharArray): CreateResult {
        try {
            val policy = PasswordPolicy.check(newPassword)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(layout.exists()) { "There is no vault to rekey." }

            val header = verifiedHeaderOrThrow()
            val vaultKey = session.copyOfVaultKey()
            try {
                val softKey = withContext(Dispatchers.Default) { deriveStoredKey(newPassword, header.salt, header.kdf) }
                try {
                    val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                    val real = realSlotOf(contents, vaultKey)
                    val decoy = 1 - real
                    // A new App Password that opens the decoy slot would open both vaults. It is refused as a weak one is.
                    if (opensSlot(newPassword, softKey, header, contents, decoy)) {
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
     *
     * The new password could be the real App Password, which must not open the decoy. So the change is counted first, as one wrong try
     * of the real vault (see [checkPasswordLocked]), before any slot is looked at, and it is never refunded. A password that opens the
     * real slot gets the same work and the same answer as any other, and the key file keeps the old decoy slot.
     */
    private suspend fun rekeyDecoyLocked(session: VaultSession, newPassword: CharArray): CreateResult {
        try {
            val policy = PasswordPolicy.check(newPassword)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(layout.exists()) { "There is no vault to change." }

            val header = verifiedHeader() ?: run {
                eraseLocked()
                return CreateResult.Locked
            }
            val store = failureStore()
            val check = store.checkDelay(clock, header.createdAtWallMs)
            if (FailureDelay.unverified(check.state)) {
                eraseLocked()
                return CreateResult.Locked
            }
            if (check.blocked) {
                spendKdfTime(header, 1)
                return CreateResult.Locked
            }
            val counted = try {
                store.recordFailure(check.state, clock)
            } catch (e: VaultException) {
                spendKdfTime(header, 1)
                return CreateResult.Locked
            } catch (e: IOException) {
                spendKdfTime(header, 1)
                return CreateResult.Locked
            }
            if (FailureDelay.erases(counted)) {
                eraseLocked()
                return CreateResult.Locked
            }
            // A key file that does not verify is unverifiable, so the vault is erased as at unlock, whatever the password. This comes after
            // the try is counted and before any slot is looked at, so a hit and a miss still take the same path.
            val contents = verifiedKeyFile(check.state) ?: run {
                eraseLocked()
                return CreateResult.Locked
            }

            val decoyKey = session.copyOfVaultKey()
            try {
                val softKey = withContext(Dispatchers.Default) { deriveStoredKey(newPassword, header.salt, header.kdf) }
                try {
                    val decoy = decoySlotOf(contents, decoyKey)
                    val real = 1 - decoy
                    // One password must never open both vaults, so a password that opens the real slot is not written. The check, the
                    // sealing and the commit run either way, and the answer is the same.
                    val hitsReal = opensSlot(newPassword, softKey, header, contents, real)
                    val (hardwareKey, decoySlot) = withHardwareAuth {
                        val key = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                        key to sealSlot(softKey, key, decoyKey, VaultRole.DECOY)
                    }
                    commitKeyFile(listOf(contents.slots[real], if (hitsReal) contents.slots[decoy] else decoySlot)) {
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

            val header = verifiedHeaderOrThrow()
            val vaultKey = session.copyOfVaultKey()
            val decoyKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            try {
                val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                val real = realSlotOf(contents, vaultKey)
                val softKey = withContext(Dispatchers.Default) { deriveStoredKey(duressPassword, header.salt, header.kdf) }
                try {
                    // The Duress Password may not open the real slot: one password must never open both vaults.
                    if (opensSlot(duressPassword, softKey, header, contents, real)) return DuressResult.SameAsAppPassword
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
     * A decoy cannot hold a decoy of its own, so Set Duress Password does nothing on a decoy session, as a decoy's change of the failure
     * limits does nothing. It runs the policy check and the time of one derivation, writes nothing, and answers [DuressResult.Enabled]. It
     * never looks at the slots, so it cannot tell whether the password is the real App Password.
     */
    private suspend fun setDuressDecoyLocked(duressPassword: CharArray): DuressResult {
        try {
            val policy = PasswordPolicy.check(duressPassword)
            if (policy is PolicyResult.Refused) return DuressResult.PolicyRejected(policy.reason)
            check(layout.exists()) { "There is no vault to protect." }
            spendKdfTime(verifiedHeaderOrThrow(), 1)
            return DuressResult.Enabled
        } finally {
            Arrays.fill(duressPassword, '\u0000')
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
        // The decoy takes the spare record files over only now, so a try that fails touches no record file of the old decoy, and writing a
        // record never adds one. They start with no value under its key. No unlock can repeat this (it holds one key, and the spares are
        // told by the real key), so a decoy that lacks a record file is not kept: its slot goes, and the setup fails.
        try {
            claimSpareRecords(vaultKey, decoyKey)
            resetDecoyRecords(decoyKey)
        } catch (e: IOException) {
            return dropDecoyAfterFailedClaim(vaultKey, contents.slots[real])
        } catch (e: VaultException) {
            return dropDecoyAfterFailedClaim(vaultKey, contents.slots[real])
        }
        // The decoy is whole. A purge that fails leaves the old decoy's files for a later one.
        bestEffort { purgeUnowned(listOf(vaultKey, decoyKey)) }
        return DuressResult.Enabled
    }

    /**
     * Undoes a committed decoy whose record files could not be taken over: the decoy slot becomes random bytes, and the files that no key
     * owns turn back into spares. Always [DuressResult.Failed]. A slot that cannot be replaced leaves a decoy that lacks record files, which
     * cannot be verified, so the vault is erased (see [eraseLocked]) instead of kept.
     */
    private fun dropDecoyAfterFailedClaim(vaultKey: ByteArray, realSlot: ByteArray): DuressResult {
        try {
            commitKeyFile(listOf(realSlot, VaultKeyFile.randomSlot(random))) { true }
        } catch (e: IOException) {
            eraseLocked()
            return DuressResult.Failed
        } catch (e: VaultException) {
            eraseLocked()
            return DuressResult.Failed
        }
        bestEffort { purgeUnowned(listOf(vaultKey)) }
        return DuressResult.Failed
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
     * A decoy session removes only its own decoy: its wallets and records are deleted. The slot stays under the Duress Password,
     * so that password opens a fresh empty decoy.
     */
    private fun removeOwnDecoyLocked(session: VaultSession) {
        check(layout.exists()) { "There is no vault to change." }
        val decoyKey = session.copyOfVaultKey()
        try {
            val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
            val decoy = decoySlotOf(contents, decoyKey)
            // Keeping the slot leaves the Duress Password working, and only the decoy's files go. Unlocking with the same password
            // lands in an empty vault rather than a refusal that names a decoy.
            try {
                commitKeyFile(listOf(contents.slots[1 - decoy], contents.slots[decoy])) { true }
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

    /** [spendKdfTime], then the refusal. A slot key that is gone for good leaves a vault no password opens, so that is erased instead. */
    private suspend fun refusedAfterSpendingKdfTime(header: VaultHeader, derivations: Int): Checked = try {
        spendKdfTime(header, derivations)
        Checked.Refused
    } catch (e: VaultKeyInvalidatedException) {
        eraseLocked()
        Checked.Erased
    }

    /**
     * [derivations] derivations with the vault's own parameters and salt, on a fixed input that is not secret. The results are
     * discarded. Runs on [Dispatchers.Default].
     */
    private suspend fun spendKdfTime(header: VaultHeader, derivations: Int) {
        val fixedInput = CALIBRATION_PASSWORD.toCharArray()
        try {
            repeat(derivations) {
                val out = withContext(Dispatchers.Default) { deriveSoftKey(fixedInput, header.salt, header.kdf) }
                Arrays.fill(out, 0)
            }
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

    /** The Vault Key and role inside a hardware-wrapped blob, or null when the blob does not open or holds no key and role. The caller zeroes the key. */
    private fun unwrapOpened(hardwareKey: SecretKey, hardwareWrapped: ByteArray): Pair<ByteArray, Byte>? {
        // A hardware layer that does not authenticate under a software layer that did holds no vault key: the vault is erased.
        val plain = keystore(HARDWARE_FAILURE) {
            try {
                Aead.openWithKeystore(hardwareKey, VaultKeyFile.HARDWARE_AAD, hardwareWrapped)
            } catch (e: AEADBadTagException) {
                return null
            }
        }
        try {
            if (plain.size != KEY_BYTES + 1) return null
            val role = plain[KEY_BYTES]
            if (role != VaultRole.REAL && role != VaultRole.DECOY) return null
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
     * Writes [slots] as the new `unlock.bin`, after reading it back. The file is committed only if every slot in [slots] is in it
     * byte for byte and [verified] holds. Otherwise the temp file is removed and nothing changes.
     */
    private fun commitKeyFile(slots: List<ByteArray>, verified: () -> Boolean) {
        // The file in place must be the one last written. An older copy put back while the vault is open would have its other slot carried
        // into the new file, and the swap recorded as the owner's. The change is refused, and the next unlock erases the vault.
        val store = failureStore()
        val previous = store.load(clock)
        if (VaultKeyFile.digestOf(readVaultFile(layout.vaultKey)) != previous.keyFileDigest) {
            throw VaultException("The vault key file is not the one last written, so the vault was not changed.")
        }
        val temp = writeTemp(layout.vaultKey, VaultKeyFile.encode(slots, random))
        var digest = ""
        val intact = try {
            val bytes = temp.readBytes()
            val contents = VaultKeyFile.decode(bytes)
            digest = VaultKeyFile.digestOf(bytes)
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
        // The failure state names the new file before the file replaces the old one, so an older copy put back later does not match. A
        // failed save or replace names the old file again, as far as it can: a save that wrote one copy leaves the new name behind. A crash
        // between the two leaves them apart, and the next unlock erases the vault.
        val nameOldFileAgain = { bestEffort { store.recordKeyFile(store.load(clock), previous.keyFileDigest, clock) } }
        try {
            store.recordKeyFile(previous, digest, clock)
        } catch (e: VaultException) {
            temp.delete()
            nameOldFileAgain()
            throw VaultException("The new vault key file could not be recorded, so the vault was not changed.", e)
        } catch (e: IOException) {
            temp.delete()
            nameOldFileAgain()
            throw VaultException("The new vault key file could not be recorded, so the vault was not changed.", e)
        }
        try {
            commitTemp(temp, layout.vaultKey)
        } catch (e: Exception) {
            nameOldFileAgain()
            throw e
        }
        // The failure state names the new file already, so a power cut must not lose the rename. The file is in place, so a failed sync
        // neither fails the change nor names the old file again.
        bestEffort { syncDirectory(layout.root) }
    }

    /**
     * The software key of a slot: `K_sw = HKDF-SHA256(Argon2id(password, salt) || HMAC(slot key, Argon2id(password, salt)), info = SLOT_INFO)`.
     * The HMAC runs in the device's Keystore on the stretched password, so each guess needs the full Argon2 work and this device. The call
     * blocks, so a caller runs it on [Dispatchers.Default]. The caller zeroes the result.
     */
    private fun deriveSoftKey(password: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        val stretched = Argon2Kdf.derive(password, salt, kdf)
        try {
            val bound = keystore(HARDWARE_FAILURE) { keys.slotMac(stretched) }
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

    /** [deriveSoftKey] of the NFC form of [password], the form every new or changed password is stored from. */
    private fun deriveStoredKey(password: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        val nfc = PasswordForm.nfcOrNull(password)
        try {
            return deriveSoftKey(nfc ?: password, salt, kdf)
        } finally {
            nfc?.fill('\u0000')
        }
    }

    /**
     * True when [password] would open slot [index], typed as it is or as its NFC form, which is how an unlock tries it. [storedKey] is the
     * key of the NFC form. An NFC [password] is also tried as its NFD form, which a 1.2.0 slot may be sealed from, since the keyboard that
     * typed it may emit either. A new password must open no slot but its own, whichever form is typed.
     */
    private suspend fun opensSlot(password: CharArray, storedKey: ByteArray, header: VaultHeader, contents: VaultKeyFile.Contents, index: Int): Boolean {
        // The other form is derived even after a match, so a match costs what a miss costs.
        val opensAsStored = openSoftLayer(storedKey, contents, index) != null
        val other = if (PasswordForm.isNfc(password)) PasswordForm.nfdOrNull(password) else password
        if (other == null) return opensAsStored
        try {
            val otherKey = withContext(Dispatchers.Default) { deriveSoftKey(other, header.salt, header.kdf) }
            try {
                return openSoftLayer(otherKey, contents, index) != null || opensAsStored
            } finally {
                Arrays.fill(otherKey, 0)
            }
        } finally {
            if (other !== password) other.fill('\u0000')
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
        ReviewPool(layout.reviewPool, key).use { pool ->
            if (keepListing) {
                owned.forEach { pool.release(it) }
            } else {
                pool.ownedNames().forEach { overwriteInPlace(File(layout.reviewPool, it)) }
            }
        }
        // The folders stay, emptied and refilled with random bytes, so the store's folder count never falls.
        pad.release(owned)
        // The record files stay in either case, so the folder's file count never changes (see [VaultRecordNames]).
        if (keepListing) {
            resetDecoyRecords(key)
        } else {
            retireRecordFiles(VaultRecordNames.KNOWN.map { File(layout.vaultRecords, VaultRecordNames.fileName(key, it)) }.filter { it.isFile })
        }
    }

    /**
     * Gives the folder of vault records its fixed set of files: one for each known name of the real vault, and as many spares under
     * random names, in the same size class as the name's record (see [VaultRecordNames]). A Decoy Vault takes the spares over (see
     * [claimSpareRecords]), so it never adds a file. No name is computable without a key, and every file is sealed or random bytes.
     */
    private fun writeRecordPlaceholders(vaultKey: ByteArray) {
        layout.vaultRecords.mkdirs()
        VaultRecordNames.KNOWN.forEach { name ->
            writeAtomically(File(layout.vaultRecords, VaultRecordNames.fileName(vaultKey, name)), VaultRecordNames.sealedUnset(vaultKey, name))
            writeAtomically(File(layout.vaultRecords, VaultRecordNames.randomFileName(random)), randomRecordFile(VaultRecordNames.fileBytesOf(name)))
        }
    }

    /** The record files that [keys] name, as a set of file names. */
    private fun recordNamesOf(keys: List<ByteArray>): Set<String> =
        keys.flatMap { key -> VaultRecordNames.KNOWN.map { VaultRecordNames.fileName(key, it) } }.toSet()

    /** The record files that none of [keys] names: the spares, and the files of a Decoy Vault that is gone. */
    private fun spareRecordFiles(keys: List<ByteArray>): List<File> {
        val named = recordNamesOf(keys)
        return layout.vaultRecords.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") && it.name !in named }
    }

    /**
     * Renames the spare record files to the names that [decoyKey] gives the known records, the largest spare to the record with the largest
     * size class and so on, so each record keeps the class of its placeholder. A rename keeps a file's size and time.
     */
    private fun claimSpareRecords(vaultKey: ByteArray, decoyKey: ByteArray) {
        val spares = spareRecordFiles(listOf(vaultKey)).shuffled(random.asKotlinRandom()).sortedByDescending { it.length() }
        if (spares.size < VaultRecordNames.KNOWN.size) throw VaultException("The folder of vault records lacks spare files.")
        VaultRecordNames.KNOWN.sortedByDescending { VaultRecordNames.fileBytesOf(it) }.zip(spares).forEach { (name, spare) ->
            if (!spare.renameTo(File(layout.vaultRecords, VaultRecordNames.fileName(decoyKey, name)))) {
                throw VaultException("A spare record file could not be renamed.")
            }
        }
    }

    /** Each decoy record file, rewritten to hold no value under [decoyKey]. The files keep their names and sizes. */
    private fun resetDecoyRecords(decoyKey: ByteArray) {
        VaultRecordNames.KNOWN.forEach { name ->
            val file = File(layout.vaultRecords, VaultRecordNames.fileName(decoyKey, name))
            writeAtomically(file, VaultRecordNames.sealedUnset(decoyKey, name, sizeOf(file)))
        }
    }

    /**
     * Turns [files] back into spares: random bytes of the same size under fresh random names, so nothing shows that a Decoy Vault
     * was there. The file count stays the same.
     */
    private fun retireRecordFiles(files: List<File>) {
        files.forEach { file ->
            writeAtomically(File(layout.vaultRecords, VaultRecordNames.randomFileName(random)), randomRecordFile(sizeOf(file)))
            file.delete()
        }
    }

    /** The size class of [file]: a record file is always one, so this is its length. */
    private fun sizeOf(file: File): Int = VaultRecordNames.classFor(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    /** Random bytes that start with the version byte, so a spare file reads like a sealed record (see [WalletFolder]'s unused files). */
    private fun randomRecordFile(size: Int): ByteArray = ByteArray(size).also {
        random.nextBytes(it)
        it[0] = BLOB_VERSION
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
                layout.walletKeyFile(id).isFile && keep.none { ownsWallet(keys, layout, it, id) }
            }.orEmpty()
            pad.release(dropped)
        }
        // The files that no kept key names, a gone decoy's or a failed one's, become spares again, so the file count stays the same.
        retireRecordFiles(spareRecordFiles(keep))
        ReviewPool.shredExcept(layout.reviewPool, keep.flatMap { key -> ReviewPool(layout.reviewPool, key).use { it.ownedNames() } }.toSet())
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

    private fun failureStore(): FailureStore = FailureStore(layout.root, keys, readBootCount)

    /**
     * The header in `header.bin`, when its MAC verifies under the header key. Null means unverifiable: the caller erases the vault, or refuses
     * its change, before any password is checked. A header of another format version is unverifiable too, and so is one whose MAC hits a
     * Keystore fault twice (see [headerMacForCheck]). A file that cannot be read throws [VaultException], as before.
     */
    private fun verifiedHeader(): VaultHeader? {
        val file = readVaultFile(layout.header)
        return try {
            VaultHeader.decodeFile(file) { input -> headerMacForCheck(input) }
        } catch (e: VaultException) {
            null
        }
    }

    /**
     * The header MAC for a check. A Keystore fault while the MAC is made is not a MAC that does not match, so the MAC is made once more before the
     * fault counts. A MAC that does not match is not made again: [VaultHeader.decodeFile] rejects it at once.
     */
    private fun headerMacForCheck(input: ByteArray): ByteArray = try {
        headerMacOf(input)
    } catch (e: VaultException) {
        headerMacOf(input)
    }

    /**
     * The contents of `unlock.bin`, or null when it is unverifiable, so the caller erases the vault: its digest is not the one in [state]
     * (an older copy was put back, or the file was edited), or it is not a key file of this format (another version, a wrong size or
     * magic). A null [state], one that could not be read, skips the digest check. A file that cannot be read throws [VaultException], which
     * is not a verdict on the file.
     */
    private fun verifiedKeyFile(state: FailureState?): VaultKeyFile.Contents? {
        val bytes = readVaultFile(layout.vaultKey)
        if (state != null && VaultKeyFile.digestOf(bytes) != state.keyFileDigest) return null
        return try {
            VaultKeyFile.decode(bytes)
        } catch (e: VaultException) {
            null
        }
    }

    /** [verifiedHeader] for a change that needs the header. Without a verified header nothing is written, and the next unlock erases the vault. */
    private fun verifiedHeaderOrThrow(): VaultHeader = verifiedHeader() ?: throw VaultException(HEADER_UNVERIFIED)

    /** The MAC of [input] under the header key. A Keystore fault throws [VaultException]. */
    private fun headerMacOf(input: ByteArray): ByteArray = keystore(HEADER_UNVERIFIED) { keys.headerMac(input) }

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
    private fun createFailureState(keyFileDigest: String): Boolean = try {
        failureStore().initialize(clock, keyFileDigest)
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
        const val HEADER_UNVERIFIED = "The vault header does not verify."
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
 * The erase starts by writing [VaultLayout.wipeMarker], which hides the vault (see [VaultLayout.exists]). The marker goes only once no file
 * is left, so an erase that stops half way, by a file that cannot be deleted or by a process that dies, is finished by
 * [Vault.finishPendingWipe] at the next gate. A marker that cannot be written, by a full disk or a folder that is not writable, does not
 * stop the erase: the keys and every file still go, as far as they can, so the vault is never kept. [Vault.finishPendingWipe] cannot finish
 * such an erase, since nothing records it. A file left behind is already overwritten, so it no longer verifies and the next unlock attempt
 * erases it.
 *
 * @throws VaultException if the marker cannot be written, if some file could not be removed, or if [deleteKeys] threw.
 */
fun wipeVault(layout: VaultLayout, deleteKeys: () -> Unit) {
    val markerFailure = try {
        markWipe(layout)
        null
    } catch (e: VaultException) {
        e
    }
    val keyFailure = try {
        deleteKeys()
        null
    } catch (e: Exception) {
        // Any failure, not only a Keystore one, still lets the files go.
        e as? VaultException ?: VaultException("The vault keys could not be deleted.", e)
    }
    // Each file is overwritten before it is deleted, so no record, key file, pool slot or photo survives in the storage.
    overwriteFiles(layout.root)
    overwriteFiles(layout.photos)
    FailureStore.forget(layout.root)
    // A root that exists but cannot be listed still holds files that could not be deleted, so it does not count as empty.
    val rootFiles = layout.root.listFiles()
    val rootGone = if (rootFiles == null) {
        !layout.root.exists()
    } else {
        rootFiles.filter { it != layout.wipeMarker }.map { it.deleteRecursively() }.all { it }
    }
    val photosGone = layout.photos.deleteRecursively()
    // A plain video that an interrupted recording left in the cache goes with the vault.
    val recordingGone = layout.shredRecording()
    if (!rootGone || !photosGone || !recordingGone || !(layout.wipeMarker.delete() || !layout.wipeMarker.exists())) {
        throw VaultException("Some vault files could not be deleted.", keyFailure ?: markerFailure)
    }
    layout.root.delete()
    // Nothing is left that the keys protect. A key that could not be deleted is inert, and the next create replaces it.
    if (keyFailure != null) throw keyFailure
}

/** Writes the erase marker before anything is deleted. The caller erases without it when it cannot be written. */
private fun markWipe(layout: VaultLayout) {
    try {
        layout.root.mkdirs()
        writeAtomically(layout.wipeMarker, ByteArray(0))
    } catch (e: IOException) {
        throw VaultException("The erase marker could not be written.", e)
    }
    // The rename should reach the disk before the first key goes, or a power cut could leave the files without their keys and without the
    // marker. A folder that cannot be synced does not stop the erase: the marker is written, and the journal commits it within seconds.
    try {
        syncDirectory(layout.root)
    } catch (e: IOException) {
        // Best effort, see above.
    }
}

/** Overwrites every file under [dir] in place. A file that cannot be overwritten is still deleted by the caller. */
private fun overwriteFiles(dir: File) {
    dir.walkTopDown().filter { it.isFile }.forEach { overwriteBestEffort(it) }
}

/** Overwrites the files in [dir], then deletes them. The folder stays, since both photo folders exist whether or not a decoy does. */
private fun shredFolderContents(dir: File) {
    overwriteFiles(dir)
    dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
}

/**
 * Removes a dropped Decoy Vault's photos, and puts placeholder bytes back in its key file, so the photo folder lists the same files
 * whether or not a decoy exists. A key file that cannot be written keeps its old bytes, which reveal nothing without the decoy's password.
 */
private fun dropDecoyPhotos(layout: VaultLayout) {
    shredFolderContents(layout.photoDir(decoy = true))
    try {
        writeAtomically(layout.photoKeyFile(decoy = true), placeholderPhotoKey())
    } catch (e: IOException) {
        // The next unlock of the decoy would replace it anyway; the drop itself must not fail half way.
    }
}
