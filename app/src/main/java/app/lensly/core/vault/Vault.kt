package app.lensly.core.vault

import android.content.Context
import android.os.Build
import app.lensly.BuildConfig
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
     * The password was wrong, or a failure delay is running. Both cases return this same value, and it carries
     * no timing or remaining-delay information.
     */
    data object Rejected : UnlockResult
}

/** Outcome of [Vault.setFailureDelayCap]. */
sealed interface FailureCapResult {
    /**
     * The password was this vault's own, and the change is accepted. From the decoy, nothing is written, so the change appears
     * to succeed without reaching the real cap.
     */
    data object Saved : FailureCapResult

    /** The password was not this vault's own, or a delay is running. Nothing was saved. */
    data object Rejected : FailureCapResult
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
class VaultException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The App Password vault, and its optional Duress Password.
 *
 * The Vault Key is protected by two layers, and both are needed to recover it:
 * 1. Software: `K_sw = Argon2id(password)`, used with AES-256-GCM to wrap the hardware blob.
 * 2. Hardware: a non-exportable Keystore AES-256-GCM key (`lensly.vault.hw`) wraps the Vault Key.
 *
 * `vault.key` holds two slots: one for the real vault and one for the Decoy Vault that a Duress Password opens. Its size
 * never changes (see [VaultKeyFile]). [unlock] derives `K_sw` once and tries it on both slots, so the Duress Password
 * costs what the App Password costs, and a refused password costs the same. Storage and timing do not say which vault
 * opened. Only [VaultSession.isDecoy] says so, and the app must never show it.
 *
 * [unlock] rate-limits password checks with [FailureDelay], and both passwords share that delay. The cap is stored in the
 * failure state, so it is the same for both. While a delay runs, [unlock] returns [UnlockResult.Rejected] without checking
 * the password. It still spends the same Argon2 time as a real check, so the delay cannot be seen from timing.
 *
 * Argon2 runs on [Dispatchers.Default]. File and Keystore work runs on [Dispatchers.IO]. The password arrays passed to
 * [create], [unlock], [rekey], [setDuress] and [setFailureDelayCap] are zeroed before those calls return.
 *
 * @param clock time source for the failure delay. Tests can inject a clock they control.
 */
class Vault internal constructor(
    private val layout: VaultLayout,
    private val keys: KeystoreBackend,
    private val clock: Clock,
    private val kdfOverride: KdfParams?,
    /** True when the packaged app allows backup. Such a build refuses to create a vault, see [refusal]. */
    private val backupAllowed: Boolean = false,
) {
    constructor(context: Context, clock: Clock = RealClock) : this(context, clock, null)

    internal constructor(context: Context, clock: Clock, kdfOverride: KdfParams?) : this(
        layout = VaultLayout(File(context.applicationContext.noBackupFilesDir, "vault")),
        keys = AndroidKeystoreBackend(context.applicationContext),
        clock = clock,
        kdfOverride = kdfOverride,
        backupAllowed = BackupExclusion.allowsBackup(context.applicationInfo.flags),
    )

    private val mutex = Mutex()
    private val random = SecureRandom()

    /** True once a vault has been created and not wiped. */
    fun exists(): Boolean = layout.exists()

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
     * @throws IllegalStateException if a vault already exists. Call [wipe] first.
     */
    suspend fun create(password: CharArray): CreateResult =
        withContext(Dispatchers.IO) { mutex.withLock { createLocked(password) } }

    /**
     * Unlocks the vault with [password]. The App Password opens the real vault, and a Duress Password opens the Decoy
     * Vault. The session's [VaultSession.isDecoy] says which one opened.
     *
     * Returns [UnlockResult.Rejected] for a password that opens neither, and for any attempt made during a failure delay.
     * A delay attempt does not check the password and does not add to the failure count, but it spends the same Argon2
     * time as a real check.
     *
     * @throws VaultException if the password is correct but the stored state is damaged or the hardware key is gone.
     */
    suspend fun unlock(password: CharArray): UnlockResult =
        withContext(Dispatchers.IO) { mutex.withLock { unlockLocked(password) } }

    /**
     * Saves the Failure Delay cap: [capMs] in milliseconds, within [FailureDelay.MIN_CAP_MS] to [FailureDelay.MAX_CAP_MS],
     * or null for Default. The password is checked exactly as [unlock] checks it, so a running delay refuses the change and
     * a wrong password counts as a failure. A right one resets the failure count. The cap lives in the failure store, which
     * both vaults share. Only the real vault writes it. A call from a decoy session ([inDecoy] true) takes only the Duress
     * Password, and its change is accepted but nothing is written, so a decoy never changes the real cap. The check runs in
     * every case, so a call takes as long as a save. The cap applies before unlock. [password] is zeroed before this returns.
     *
     * @throws IllegalArgumentException if [capMs] is outside the offered range.
     */
    suspend fun setFailureDelayCap(password: CharArray, capMs: Long?, inDecoy: Boolean = false): FailureCapResult =
        withContext(Dispatchers.IO) { mutex.withLock { setFailureDelayCapLocked(password, capMs, inDecoy) } }

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
     * Deletes the vault: its Keystore keys first, then every file, the real vault and the decoy vault alike. Call this only
     * on an explicit user command. Do not call it while any other operation of this class is running.
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
    internal fun failureCountForTesting(): Int = failureStore().load(clock).failures

    /**
     * True on an emulator, through the same check the secure-window rule uses. JVM tests have no device values, so a
     * missing one counts as a real device.
     */
    private fun deviceIsEmulator(): Boolean {
        val hardware = Build.HARDWARE ?: return false
        val product = Build.PRODUCT ?: return false
        val fingerprint = Build.FINGERPRINT ?: return false
        return isEmulator(hardware, product, fingerprint)
    }

    private suspend fun createLocked(password: CharArray): CreateResult {
        try {
            if (backupAllowed) return CreateResult.BackupAllowed
            val policy = PasswordPolicy.check(password)
            if (policy is PolicyResult.Refused) return CreateResult.PolicyRejected(policy.reason)
            check(!layout.exists()) { "A vault already exists. Call wipe() before creating another." }

            val hardwareKey = keystoreOrNull { keys.createHardwareKey() } ?: return CreateResult.DeviceUnsupported
            val macKey = keystoreOrNull { keys.createFailureMacKey() } ?: return CreateResult.DeviceUnsupported
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

            val salt = ByteArray(VaultHeader.SALT_BYTES).also { random.nextBytes(it) }
            val vaultKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
            try {
                val kdf = kdfOverride ?: withContext(Dispatchers.Default) { calibrateKdf() }
                val softKey = withContext(Dispatchers.Default) { Argon2Kdf.derive(password, salt, kdf) }
                try {
                    val realSlot = sealSlot(softKey, hardwareKey, vaultKey, VaultRole.REAL)
                    val decoySlot = VaultKeyFile.randomSlot(random)
                    layout.root.mkdirs()
                    FailureStore(layout.root, macKey).initialize(clock)
                    writeAtomically(layout.vaultKey, VaultKeyFile.encode(listOf(realSlot, decoySlot), random))
                    // The store starts with its full set of wallet folders, so it never shows an empty one.
                    WalletPad(layout) { hardwareKey }.padTo(WALLET_SLOT_STEP)
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
            val opened = checkPasswordLocked(password) ?: return UnlockResult.Rejected
            try {
                if (opened.legacy) migrateToCurrentLayout(opened.softKey, opened.hardwareKey, opened.vaultKey)
                val session = VaultSession(
                    layout,
                    opened.vaultKey.copyOf(),
                    isDecoy = opened.role == VaultRole.DECOY,
                    pad = WalletPad(layout) { opened.hardwareKey },
                )
                // Records written under plain names are renamed, so that the two vaults never share a file name.
                if (!session.isDecoy) session.migrateLegacyRecordNames()
                // An older store has one folder per wallet. The first unlock after the upgrade pads it to its full size.
                session.ensurePadded()
                return UnlockResult.Unlocked(session)
            } finally {
                opened.close()
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private suspend fun setFailureDelayCapLocked(password: CharArray, capMs: Long?, inDecoy: Boolean): FailureCapResult {
        try {
            if (capMs != null) {
                require(capMs in FailureDelay.MIN_CAP_MS..FailureDelay.MAX_CAP_MS) { "cap must be between 1 s and 1 h" }
            }
            val opened = checkPasswordLocked(password) ?: return FailureCapResult.Rejected
            try {
                // Each vault takes only its own password. The cap is shared by both vaults, so only the real vault writes it. A
                // decoy's change is accepted and nothing is written, so a coerced change cannot weaken the real cap.
                val own = if (inDecoy) VaultRole.DECOY else VaultRole.REAL
                if (opened.role != own) return FailureCapResult.Rejected
                if (!inDecoy) opened.store.recordCap(opened.state, capMs)
                return FailureCapResult.Saved
            } finally {
                opened.close()
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    /** A password that opened one slot: its Vault Key and role, and the software key. The caller closes it. */
    private class OpenedSlot(
        val softKey: ByteArray,
        val vaultKey: ByteArray,
        val role: Byte,
        val legacy: Boolean,
        val hardwareKey: SecretKey,
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
     * Checks [password] against the Failure Delay and against both slots. Returns null when the attempt is refused: during a
     * delay, which is not checked and not counted, or for a password that opens neither slot, which is counted. Otherwise it
     * returns the slot the password opened, and the caller closes it. Both slots are tried with the one derivation, so the
     * decoy costs what the real vault costs, and a refused password costs the same as either.
     */
    private suspend fun checkPasswordLocked(password: CharArray): OpenedSlot? {
        if (!layout.exists()) return null
        val header = VaultHeader.decode(layout.header.readBytes())
        val store = failureStore()
        val state = store.load(clock)
        // During a delay the password is not checked, so it counts as neither a pass nor a failure. The KDF still runs
        // once, on a fixed input, so a refused attempt takes as long as a real one.
        if (FailureDelay(header.createdAtWallMs, FailureDelay.configuredCapFor(state)).isBlocked(state, clock)) {
            spendKdfTime(header)
            return null
        }

        val softKey = withContext(Dispatchers.Default) { Argon2Kdf.derive(password, header.salt, header.kdf) }
        var handedOver = false
        try {
            val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
            val opened = contents.slots.indices.mapNotNull { index ->
                openSoftLayer(softKey, contents, index)?.let { index to it }
            }
            if (opened.size > 1) {
                throw VaultException("The vault key file is damaged: two slots open with one password.")
            }
            if (opened.isEmpty()) {
                store.recordFailure(state, clock)
                return null
            }
            // The software layer authenticated, so the password is right. Reset the count before the hardware step.
            val reset = store.recordSuccess(state)
            val hardwareKey = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
            val (vaultKey, role) = unwrapOpened(hardwareKey, contents.legacy, opened.single().second)
            handedOver = true
            return OpenedSlot(softKey, vaultKey, role, contents.legacy, hardwareKey, store, reset)
        } finally {
            if (!handedOver) Arrays.fill(softKey, 0)
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
                    val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                    val real = realSlotOf(contents, vaultKey)
                    val decoy = 1 - real
                    // A new App Password that opens the decoy slot would open both vaults. It is refused as a weak one is.
                    if (openSoftLayer(softKey, contents, decoy) != null) {
                        return CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE)
                    }
                    val hardwareKey = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                    val realSlot = sealSlot(softKey, hardwareKey, vaultKey, VaultRole.REAL)
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
                val softKey = withContext(Dispatchers.Default) { Argon2Kdf.derive(newPassword, header.salt, header.kdf) }
                try {
                    val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
                    val decoy = decoySlotOf(contents, decoyKey)
                    val real = 1 - decoy
                    // The decoy's new password may not open the real slot: one password must never open both vaults.
                    if (openSoftLayer(softKey, contents, real) != null) {
                        return CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE)
                    }
                    val hardwareKey = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
                    val decoySlot = sealSlot(softKey, hardwareKey, decoyKey, VaultRole.DECOY)
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
                val softKey = withContext(Dispatchers.Default) {
                    Argon2Kdf.derive(duressPassword, header.salt, header.kdf)
                }
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
    private fun installDecoy(
        vaultKey: ByteArray,
        decoyKey: ByteArray,
        softKey: ByteArray,
        contents: VaultKeyFile.Contents,
        real: Int,
        populate: (VaultSession) -> Unit,
    ): DuressResult {
        val decoy = VaultSession(layout, decoyKey.copyOf(), isDecoy = true, pad = walletPad())
        try {
            populate(decoy)
            if (decoy.listWallets().isEmpty()) {
                discard(decoyKey)
                return DuressResult.DecoyEmpty
            }
        } catch (e: Exception) {
            discard(decoyKey)
            return DuressResult.Failed
        } finally {
            decoy.close()
        }

        val committed = try {
            val hardwareKey = keystore(HARDWARE_FAILURE) { keys.hardwareKey() }
            val decoySlot = sealSlot(softKey, hardwareKey, decoyKey, VaultRole.DECOY)
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
            discard(decoyKey)
            return DuressResult.Failed
        }
        purgeUnowned(listOf(vaultKey, decoyKey))
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
            purgeUnowned(listOf(vaultKey))
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
            discard(decoyKey, keepListing = true)
        } finally {
            Arrays.fill(decoyKey, 0)
        }
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

    /**
     * The hardware-wrapped blob of slot [index] if [softKey] opens its software layer, or null. The attempt costs the same
     * whether or not it succeeds, and it never reports which slot a password is for.
     */
    private fun openSoftLayer(softKey: ByteArray, contents: VaultKeyFile.Contents, index: Int): ByteArray? {
        val aad = if (contents.legacy) VaultKeyFile.LEGACY_SOFTWARE_AAD else VaultKeyFile.SOFTWARE_AAD
        return try {
            Aead.open(softKey, aad, contents.sealedOf(index))
        } catch (e: AEADBadTagException) {
            null
        }
    }

    /** The Vault Key and role inside a hardware-wrapped blob. The caller zeroes the key. */
    private fun unwrapOpened(hardwareKey: SecretKey, legacy: Boolean, hardwareWrapped: ByteArray): Pair<ByteArray, Byte> {
        val aad = if (legacy) VaultKeyFile.LEGACY_HARDWARE_AAD else VaultKeyFile.HARDWARE_AAD
        val plain = keystore(HARDWARE_FAILURE) { Aead.openWithKeystore(hardwareKey, aad, hardwareWrapped) }
        try {
            if (legacy) {
                if (plain.size != KEY_BYTES) throw VaultException("The vault key is damaged.")
                return plain.copyOf() to VaultRole.REAL
            }
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

    /** Rewrites a legacy `vault.key` in the current layout: the same Vault Key and software key, and an empty decoy slot. */
    private fun migrateToCurrentLayout(softKey: ByteArray, hardwareKey: SecretKey, vaultKey: ByteArray) {
        val realSlot = sealSlot(softKey, hardwareKey, vaultKey, VaultRole.REAL)
        val decoySlot = VaultKeyFile.randomSlot(random)
        try {
            commitKeyFile(listOf(realSlot, decoySlot)) {
                unwrapsTo(softKey, hardwareKey, realSlot, vaultKey + VaultRole.REAL)
            }
        } catch (e: IOException) {
            throw VaultException("The vault key could not be upgraded to the current layout.", e)
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

    /** Deletes the wallets and vault records that [key] owns. This drops a decoy that was being set up, or wipes the decoy. */
    private fun discard(key: ByteArray, keepListing: Boolean = false) {
        val owned = layout.wallets.list()?.filter { ownsWallet(layout, key, it) }.orEmpty()
        // The folders stay, emptied and refilled with random bytes, so the store's folder count never falls.
        walletPad().release(owned)
        VaultRecordNames.KNOWN.forEach { name ->
            val file = File(layout.vaultRecords, VaultRecordNames.fileName(key, name))
            if (!file.isFile) return@forEach
            // A wipe keeps the name and size of the record file, so the store's file shape does not change.
            if (keepListing) overwriteWithRandom(file) else file.delete()
        }
    }

    /**
     * Deletes what neither key in [keep] owns: wallet folders that hold a key file, and vault record files. A folder without
     * a key file is still being written, so it is left alone. This is how a dropped Decoy Vault's files go. A padded store
     * keeps each dropped folder as an unused slot, so the folder count stays the same.
     */
    private fun purgeUnowned(keep: List<ByteArray>) {
        val pad = walletPad()
        val dropped = layout.wallets.list()?.filter { id ->
            val dir = layout.walletDir(id)
            File(dir, WALLET_KEY_FILE_NAME).isFile && keep.none { ownsWallet(layout, it, id) }
        }.orEmpty()
        pad.release(dropped)
        val kept = keep.flatMap { key -> VaultRecordNames.KNOWN.map { VaultRecordNames.fileName(key, it) } }.toSet()
        layout.vaultRecords.listFiles()?.forEach { file ->
            if (file.name.endsWith(".bin") && file.name !in kept) file.delete()
        }
    }

    /** The unused wallet slots of this store. Every session the vault opens shares them. */
    private fun walletPad(): WalletPad = WalletPad(layout) { keystore(HARDWARE_FAILURE) { keys.hardwareKey() } }

    /** Writes random bytes over [file], keeping its length. Used when a wipe must not change the store's file shape. */
    private fun overwriteWithRandom(file: File) {
        val size = file.length().toInt()
        if (size <= 0) return
        file.writeBytes(ByteArray(size).also { random.nextBytes(it) })
    }

    private fun failureStore(): FailureStore = FailureStore(layout.root, keys.failureMacKey())

    private companion object {
        const val KEY_BYTES = VaultKeyFile.KEY_BYTES
        const val UNKNOWN_SECURITY_LEVEL = -1
        const val HARDWARE_FAILURE = "The vault's hardware key cannot unwrap the vault key."
        const val CALIBRATION_PASSWORD = "lensly calibration"

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

/**
 * Deletes a vault: its Keystore keys first (through [deleteKeys]), then every file under its root. Without the keys the
 * wrapped vault key is unrecoverable even if some file survives.
 *
 * @throws VaultException if some file could not be removed. The keys are already gone by then.
 */
internal fun wipeVault(layout: VaultLayout, deleteKeys: () -> Unit) {
    deleteKeys()
    FailureStore.forget(layout.root)
    if (!layout.root.deleteRecursively()) throw VaultException("Some vault files could not be deleted.")
}
