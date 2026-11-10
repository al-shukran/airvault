package app.lensly.core.vault

/**
 * Failure bookkeeping as persisted by [FailureStore].
 *
 * [seq] grows on every write, so an older copy of the file can be recognised as a rollback.
 * The two `lastFailure` readings are the wall-clock and monotonic times at the most recent failure.
 */
internal data class FailureState(
    val seq: Long,
    val failures: Int,
    val lastFailureWallMs: Long,
    val lastFailureElapsedMs: Long,
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

    /** True while the delay that follows [state]'s last failure has not yet run out. */
    fun isBlocked(state: FailureState, clock: Clock): Boolean {
        val delay = delayFor(state.failures, clock.wallMillis())
        return delay > 0 && elapsedSinceFailure(state, clock) < delay
    }

    companion object {
        const val BASE_DELAY_MS = 10_000L
        const val LEARNING_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
        const val LEARNING_CAP_MS = 10_000L
        const val DEFAULT_CAP_MS = 60_000L
        const val MIN_CAP_MS = 1_000L
        const val MAX_CAP_MS = 3_600_000L

        /** From this exponent on, `BASE_DELAY_MS shl exponent` exceeds every cap, so the cap applies. */
        private const val MAX_EXPONENT = 40

        /**
         * Milliseconds elapsed since the failure recorded in [state].
         *
         * Within one boot the monotonic clock is used, and it cannot be moved by changing the wall clock.
         * The result is the smaller of the monotonic and wall readings, so a wall clock moved forward
         * cannot shorten the delay. After a reboot the monotonic clock has restarted, so only the wall
         * clock is available. A negative result means the wall clock moved backwards, which
         * [FailureStore] treats as tampering.
         */
        fun elapsedSinceFailure(state: FailureState, clock: Clock): Long {
            val wallElapsed = clock.wallMillis() - state.lastFailureWallMs
            val monotonicNow = clock.elapsedMillis()
            if (monotonicNow < state.lastFailureElapsedMs) return wallElapsed
            return minOf(monotonicNow - state.lastFailureElapsedMs, wallElapsed)
        }
    }
}
