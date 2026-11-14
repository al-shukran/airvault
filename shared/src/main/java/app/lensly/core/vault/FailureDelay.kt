package app.lensly.core.vault

/*
 * Threat model for the Failure Delay. The attacker holds the phone, can edit the app's files (root, or a restored backup), reboot
 * it, set its clocks, write Settings.Global.BOOT_COUNT, and make the Keystore or the storage fail. Each attack and its defence:
 * 1. Restore an older pair of copies: every save MACs under a new Keystore key and deletes the older keys, so the older pair does
 *    not verify. A pair older than the newest key the Keystore still lists is tampered too, because a deletion may have failed.
 *    Tampering locks at the cap of a copy that still verifies, or at the strictest cap (1 h) when none does.
 * 2. Delete one copy: the other still verifies, so the owner's cap is kept and the missing copy is rewritten.
 * 3. Delete both copies, or every key: nothing verifies, so the state locks at the strictest cap.
 * 4. Reboot: the served time is saved at each check. A monotonic reading below the save time proves that the clock restarted, and
 *    the wait goes on from the served time plus the time since that boot, never from zero.
 * 5. Move the wall clock forward within one boot: the monotonic reading bounds the wait, so the jump cannot shorten it.
 * 6. Move the wall clock back past the failure: a negative reading is tampering.
 * 7. Move the wall clock forward across a reboot: the wall clock never credits more than the served time plus the time since the
 *    boot, with or without a boot count, so the jump cannot end the wait early.
 * 8. Raise BOOT_COUNT without a reboot: the monotonic clock did not restart, so the count is not believed. The wait is measured on
 *    the monotonic clock alone, which cannot overstate the served time.
 * 9. Lower BOOT_COUNT: no reboot does that, so the state is tampered and locks at the strictest cap.
 * 10. Make BOOT_COUNT unreadable: the recorded count is not compared, and items 4, 5, 6 and 7 still hold.
 * 11. Make the Keystore or the storage fail: no error is thrown past FailureStore. A failed check that cannot be saved deletes the
 *    copies and locks at the strictest cap. A lock that cannot be saved is held in memory with the time it was first made, so
 *    repeated attempts cannot extend it: the owner waits at most one hour from that time. A correct password is never refused for good.
 * 12. Time the attempts: a blocked attempt saves once, as a failed or a passing attempt does, whether or not BOOT_COUNT is readable.
 * 13. Replay an older payload: a reset is never written under a key that is already on disk, because two payloads would then verify
 *    under it. While no new key can be made, the reset stays in memory for this process.
 * 14. Crash between the write of a save and the deletion of the old key: the deletion is repeated on every load and save, so the
 *    next load recovers it.
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
 */
data class FailureState(
    val seq: Long,
    val failures: Int,
    val lastFailureWallMs: Long,
    val lastFailureElapsedMs: Long,
    val capMs: Long? = null,
    val bootCount: Int? = null,
    val elapsedAtSave: Long = 0L,
)

/**
 * Failure-delay policy for App Password checks.
 *
 * After `failures >= 1` consecutive failed checks the delay is `min(cap, BASE_DELAY_MS * 2^(failures - 1))`,
 * and it is zero with no failures. For [LEARNING_PERIOD_MS] after vault creation the cap is
 * [LEARNING_CAP_MS] (or [configuredCapMs] if that is smaller). After that it is [configuredCapMs].
 */
class FailureDelay(
    private val vaultCreatedWallMs: Long,
    private val configuredCapMs: Long = DEFAULT_CAP_MS,
) {
    init {
        require(configuredCapMs in MIN_CAP_MS..MAX_CAP_MS) { "cap must be between 1 s and 1 h" }
    }

    /** The cap in force at wall-clock time [nowWallMs]. */
    fun capMs(nowWallMs: Long): Long =
        if (nowWallMs - vaultCreatedWallMs < LEARNING_PERIOD_MS) {
            minOf(LEARNING_CAP_MS, configuredCapMs)
        } else {
            configuredCapMs
        }

    /** The delay that follows [failures] consecutive failed checks, evaluated at [nowWallMs]. */
    fun delayFor(failures: Int, nowWallMs: Long): Long {
        if (failures <= 0) return 0L
        val cap = capMs(nowWallMs)
        val exponent = failures - 1
        if (exponent >= MAX_EXPONENT) return cap
        return minOf(cap, BASE_DELAY_MS shl exponent)
    }

    /**
     * True while the delay that follows [state]'s last failure has not yet run out. [bootCount] is the device's current
     * `Settings.Global.BOOT_COUNT`, or null when it could not be read.
     */
    fun isBlocked(state: FailureState, clock: Clock, bootCount: Int? = null): Boolean {
        val delay = delayFor(state.failures, clock.wallMillis())
        return delay > 0 && elapsedSinceFailure(state, clock, bootCount) < delay
    }

    companion object {
        const val BASE_DELAY_MS = 10_000L
        const val LEARNING_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
        const val LEARNING_CAP_MS = 10_000L
        const val DEFAULT_CAP_MS = 60_000L
        const val MIN_CAP_MS = 1_000L
        const val MAX_CAP_MS = 3_600_000L

        /** A cap below this is offered only after the owner confirms the spec warning. */
        const val WARNING_BELOW_CAP_MS = 10_000L

        /** The cap that applies to [state]: the owner's choice, or [DEFAULT_CAP_MS] for Default. */
        fun configuredCapFor(state: FailureState): Long = state.capMs ?: DEFAULT_CAP_MS

        /** True when choosing [capMs] must show the spec warning first. Default ([capMs] null) never does. */
        fun warnsAt(capMs: Long?): Boolean = capMs != null && capMs < WARNING_BELOW_CAP_MS

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
