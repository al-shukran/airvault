package app.lensly.core.vault

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
internal data class FailureState(
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
internal class FailureDelay(
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
         * Milliseconds elapsed since the failure recorded in [state]. It is never more than the real time that has passed, so
         * the wait ends only after the delay has really run.
         *
         * [bootCount] is the device's current `Settings.Global.BOOT_COUNT`, or null when it could not be read.
         * - Same boot count: the result is the smaller of the monotonic and wall readings, so a wall clock moved forward
         *   cannot shorten the delay.
         * - A different boot count means the device rebooted, and the monotonic clock restarted with it. The wait then
         *   continues from [FailureState.elapsedAtSave], plus the time since this boot, and the wall clock is not used.
         *   Moving the wall clock forward or back therefore cannot end the wait early. A count that a root user edits goes
         *   the same way, and can only make the wait longer.
         * - No boot count on either side: the monotonic clock is used alone while it did not go back, and the wall clock
         *   carries the wait when it did, which is the reboot it can see.
         *
         * A negative result means the wall clock moved backwards within one boot, which [FailureStore] treats as tampering.
         */
        fun elapsedSinceFailure(state: FailureState, clock: Clock, bootCount: Int? = null): Long {
            val monotonicNow = clock.elapsedMillis()
            val wallElapsed = clock.wallMillis() - state.lastFailureWallMs
            val storedBootCount = state.bootCount
            if (storedBootCount != null && bootCount != null) {
                if (bootCount != storedBootCount) return state.elapsedAtSave + monotonicNow
                return minOf(monotonicNow - state.lastFailureElapsedMs, wallElapsed)
            }
            if (monotonicNow < state.lastFailureElapsedMs) return wallElapsed
            return minOf(monotonicNow - state.lastFailureElapsedMs, wallElapsed)
        }
    }
}
