package app.lensly.core.vault

/*
 * Threat model for the Failure Delay. The attacker holds the phone, can edit the app's files (root, or a restored backup), reboot
 * it, set its clocks, write Settings.Global.BOOT_COUNT, and make the Keystore or the storage fail. Each attack and its defence:
 * 1. Restore an older pair of copies: every save MACs under a new Keystore key and deletes the older keys, so the older pair does
 *    not verify. A pair older than the newest key the Keystore still lists is tampered too, because a deletion may have failed.
 *    Tampering keeps the count and the cap of the copies that verify, and restarts the wait at the full cap (item 6). With no copy
 *    verifying, the count is the tamper count, which erases the vault at the next unlock attempt (item 15).
 * 2. Delete one copy: the other still verifies, so its count and the owner's cap are kept, and the missing copy is rewritten.
 * 3. Delete both copies, or every key: nothing verifies, so the vault is erased at the next unlock attempt (item 15).
 * 4. Reboot: the served time is saved at each check. A monotonic reading below the save time proves that the clock restarted, and
 *    the wait goes on from the served time plus the time since that boot, never from zero.
 * 5. Move the wall clock forward within one boot: the monotonic reading bounds the wait, so the jump cannot shorten it.
 * 6. Move the wall clock back past the failure: a negative reading is tampering. The copies that verify keep their failure count, so
 *    erasing still counts the wrong tries (item 15), and the wait restarts at the full cap. The wall clock never shortens a wait, so
 *    a clock that went back only lengthens it.
 * 7. Move the wall clock forward across a reboot: the wall clock never credits more than the served time plus the time since the
 *    boot, with or without a boot count, so the jump cannot end the wait early.
 * 8. Raise BOOT_COUNT without a reboot: the monotonic clock did not restart, so the count is not believed. The wait is measured on
 *    the monotonic clock alone, which cannot overstate the served time. The learning week needs the count it was made under, so a
 *    raised count ends it (item 16).
 * 9. Lower BOOT_COUNT: no reboot does that, so the state is tampered. As in item 6, the copies that verify keep their count and cap,
 *    the wait restarts at the full cap, and erasing still counts the wrong tries (item 15).
 * 10. Make BOOT_COUNT unreadable: the recorded count is not compared, and items 4, 5, 6 and 7 still hold. The learning week needs a
 *     readable count, so while it is unreadable the owner's cap applies (item 16).
 * 11. Make the Keystore or the storage fail: no error is thrown past FailureStore. Each try is counted before its password is
 *    checked. A try whose count cannot be saved gets no verdict: the vault refuses it without checking the password. A lock that
 *    cannot be saved is held in memory with the time it was first made, so repeated attempts cannot extend it. A right password is
 *    refused while its count cannot be saved, and a Keystore or storage fault that leaves no verified copy erases the vault instead
 *    (item 15).
 * 12. Time the attempts: a blocked attempt saves once, as a failed attempt does, whether or not BOOT_COUNT is readable. A passing
 *    attempt saves twice, the count and then the reset, so it makes one save more than a failed attempt does.
 * 13. Replay an older payload: a reset is never written under a key that is already on disk, because two payloads would then verify
 *    under it. While no new key can be made, the reset stays in memory for this process.
 * 14. Crash between the write of a save and the deletion of the old key: the deletion is repeated on every load and save, so the
 *    next load recovers it.
 * 15. Count wrong tries: the owner's erase count is in the MAC-protected payload, so an edit cannot change it. When the last allowed
 *    try is wrong, the vault is erased. A try is counted before its password is checked (item 11), so a try that a reboot or an
 *    error cuts short still counts. A clock or BOOT_COUNT change (items 6 and 9) keeps the count of the copies that verify, so it
 *    never stops the erase. A state with no verified copy (items 1, 3 and 11, a crash between a key and its copies, or a power cut
 *    that loses the last save) is at the erase threshold: its count is TAMPER_FAILURES, which is above every erase count, so the
 *    next unlock attempt erases the vault before the password is checked. Lensly accepts data loss over keeping a vault it cannot
 *    verify, so an accidental erase from a corrupted write or a Keystore read error is an accepted cost. A fresh vault is not in
 *    this case: its state verifies from creation, and no vault means no attempt.
 * 16. Set the clock back into the first week: the learning cap needs proof that the vault is still in its first week. The proof is the
 *     monotonic time since the vault was made, read within the boot it was made in. The boot count recorded then must still be the
 *     current one, which shows no reboot since, so a reboot ends the week for good and an unreadable count ends it while it is
 *     unreadable. A clock that reads before the vault was made ends it too. Without the proof the owner's cap applies, so the wall
 *     clock never opens the learning week.
 */

/**
 * Failure bookkeeping as persisted by [FailureStore].
 *
 * [seq] grows on every write, so an older copy of the file can be recognised as a rollback.
 * The two `lastFailure` readings are the wall-clock and monotonic times at the most recent failure, moved onto the clocks of the
 * boot in which each save is made, so the time since them is the wait served (see [FailureStore]).
 * [capMs] is the Failure Delay cap the owner chose, or null for Default, which is the spec default.
 * [bootCount] is `Settings.Global.BOOT_COUNT` when the state was written, or null when the device did not report it.
 * [elapsedAtSave] is how much of the delay had run when the state was written, so a reboot continues the wait.
 * [wipeAfter] is the owner's erase count: the vault is erased when this many wrong tries come in a row (see [FailureDelay.erases]).
 * [createdElapsedMs] is the monotonic reading when the vault was made, and [createdBootCount] the boot count then, or null when either
 * was not known. Together they prove the learning week (see [FailureDelay]).
 * [fullCapWait] is true after a clock or boot-count anomaly whose copies verified: the wait then runs the full cap, whatever the count.
 */
data class FailureState(
    val seq: Long,
    val failures: Int,
    val lastFailureWallMs: Long,
    val lastFailureElapsedMs: Long,
    val capMs: Long? = null,
    val bootCount: Int? = null,
    val elapsedAtSave: Long = 0L,
    val wipeAfter: Int = FailureDelay.DEFAULT_WIPE_AFTER,
    val createdElapsedMs: Long? = null,
    val createdBootCount: Int? = null,
    val fullCapWait: Boolean = false,
)

/**
 * Failure-delay policy for App Password checks.
 *
 * After `failures >= 1` consecutive failed checks the delay is `min(cap, BASE_DELAY_MS * 2^(failures - 1))`,
 * and it is zero with no failures. While the learning week is proven to run (see [learningWeekOpen]) the cap is [LEARNING_CAP_MS]
 * (or [configuredCapMs] if that is smaller). Otherwise it is [configuredCapMs], and so is the wait of a clock anomaly
 * (see [FailureState.fullCapWait]).
 */
class FailureDelay(
    private val vaultCreatedWallMs: Long,
    private val configuredCapMs: Long = DEFAULT_CAP_MS,
) {
    init {
        require(configuredCapMs in MIN_CAP_MS..MAX_CAP_MS) { "cap must be between 10 s and 1 h" }
    }

    /** The cap in force: the learning cap while the learning week runs, otherwise the configured cap. */
    fun capMs(learningWeek: Boolean): Long = if (learningWeek) minOf(LEARNING_CAP_MS, configuredCapMs) else configuredCapMs

    /** The delay that follows [failures] consecutive failed checks, with the learning week running or not. */
    fun delayFor(failures: Int, learningWeek: Boolean): Long {
        if (failures <= 0) return 0L
        val cap = capMs(learningWeek)
        val exponent = failures - 1
        if (exponent >= MAX_EXPONENT) return cap
        return minOf(cap, BASE_DELAY_MS shl exponent)
    }

    /**
     * The wait that [state]'s last failure starts, as seen at [clock]'s time: zero with no failures. [bootCount] is the device's current
     * `Settings.Global.BOOT_COUNT`, or null when it could not be read.
     */
    fun waitFor(state: FailureState, clock: Clock, bootCount: Int? = null): Long = when {
        state.fullCapWait -> configuredCapMs
        state.failures <= 0 -> 0L
        else -> delayFor(state.failures, learningWeekOpen(state, clock, bootCount))
    }

    /**
     * True while the delay that follows [state]'s last failure has not yet run out. [bootCount] is the device's current
     * `Settings.Global.BOOT_COUNT`, or null when it could not be read.
     */
    fun isBlocked(state: FailureState, clock: Clock, bootCount: Int? = null): Boolean {
        val delay = waitFor(state, clock, bootCount)
        return delay > 0 && elapsedSinceFailure(state, clock, bootCount) < delay
    }

    /**
     * True only when the learning week is proven to run. The proof is the monotonic time since the vault was made, read within the boot
     * it was made in: the boot count must still be the one recorded at creation, which shows that no reboot came since. A reboot leaves
     * a gap that no clock measures, so it is not credited, and the week is not proven. A wall clock that reads before the vault was made
     * is not believed either. Anything missing or unproven means the owner's cap.
     */
    private fun learningWeekOpen(state: FailureState, clock: Clock, bootCount: Int?): Boolean {
        val createdElapsedMs = state.createdElapsedMs ?: return false
        val createdBootCount = state.createdBootCount ?: return false
        if (bootCount != createdBootCount) return false
        val age = clock.elapsedMillis() - createdElapsedMs
        return age >= 0 && age < LEARNING_PERIOD_MS && clock.wallMillis() >= vaultCreatedWallMs
    }

    companion object {
        const val BASE_DELAY_MS = 10_000L
        const val LEARNING_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
        const val LEARNING_CAP_MS = 10_000L
        const val DEFAULT_CAP_MS = 60_000L
        const val MIN_CAP_MS = 10_000L
        const val MAX_CAP_MS = 3_600_000L

        /** The erase count the owner may choose, from 3 to 20. Erasing cannot be switched off. */
        const val MIN_WIPE_AFTER = 3
        const val MAX_WIPE_AFTER = 20
        const val DEFAULT_WIPE_AFTER = 10

        /** The warning starts when this many tries, or fewer, are left before the vault is erased. */
        const val WARN_TRIES_LEFT = 3

        /** The cap that applies to [state]: the owner's choice, or [DEFAULT_CAP_MS] for Default. */
        fun configuredCapFor(state: FailureState): Long = state.capMs ?: DEFAULT_CAP_MS

        /**
         * True once [state] has had its last allowed wrong try, so the vault is erased. A state with no verified copy counts as at the
         * erase threshold, because [FailureStore.TAMPER_FAILURES] is above every erase count (threat 15).
         */
        fun erases(state: FailureState): Boolean = state.failures >= state.wipeAfter

        /**
         * True for the state that no copy verifies, which [FailureStore] gives the tamper count. The unlock attempt erases the vault
         * before it checks the password, so an unverified state is never used to open the vault (threat 15).
         */
        fun unverified(state: FailureState): Boolean = state.failures >= FailureStore.TAMPER_FAILURES

        /**
         * The tries left before the vault is erased, when that is [WARN_TRIES_LEFT] or fewer and a wrong try has been made. Null
         * otherwise: with the lowest erase count, [WARN_TRIES_LEFT] already covers a vault with no wrong tries, which must not warn.
         */
        fun triesLeftToWarn(state: FailureState): Int? =
            (state.wipeAfter - state.failures).takeIf { state.failures > 0 && it in 1..WARN_TRIES_LEFT }

        /** From this exponent on, `BASE_DELAY_MS shl exponent` exceeds every cap, so the cap applies. */
        private const val MAX_EXPONENT = 40

        /**
         * Milliseconds of the wait served since the failure recorded in [state], as the least that the clocks allow. It is never more
         * than the real time that has passed, so the wait ends only after the delay has really run (see the threat model above).
         *
         * [state] was saved at monotonic time `lastFailureElapsedMs + elapsedAtSave`. A monotonic reading below that time proves that
         * the clock restarted, which is a reboot. Then the wait continues from the served time plus the time since this boot. With a
         * boot count on the state, the wall clock is not used at all. Without one, the wall clock may only shorten that credit, so a
         * moved clock cannot add to it. Without a restart, the monotonic reading alone measures the wait, and the wall clock may only
         * shorten it. A boot count that went up without a restart is not believed, because it can be written without one.
         *
         * [bootCount] is the device's current `Settings.Global.BOOT_COUNT`, or null when it could not be read. A count that went down
         * is tampering, which [FailureStore] detects with [bootCountWentBack]. A negative result means the wall clock moved backwards
         * past the failure, which [FailureStore] also treats as tampering.
         */
        fun elapsedSinceFailure(state: FailureState, clock: Clock, bootCount: Int? = null): Long {
            val monotonicNow = clock.elapsedMillis()
            val wallElapsed = clock.wallMillis() - state.lastFailureWallMs
            val savedAt = state.lastFailureElapsedMs + state.elapsedAtSave
            if (monotonicNow < savedAt) {
                // The clock restarted: the time since this boot is real time that passed after the save.
                val continued = state.elapsedAtSave + monotonicNow
                return if (state.bootCount != null) continued else minOf(wallElapsed, continued)
            }
            val monotonicElapsed = monotonicNow - state.lastFailureElapsedMs
            val storedBootCount = state.bootCount
            if (storedBootCount != null && bootCount != null && bootCount > storedBootCount) return monotonicElapsed
            return minOf(monotonicElapsed, wallElapsed)
        }

        /** True when the device reports a boot count below the one recorded with [state], which no reboot can cause. */
        fun bootCountWentBack(state: FailureState, bootCount: Int?): Boolean {
            val stored = state.bootCount ?: return false
            return bootCount != null && bootCount < stored
        }
    }
}
