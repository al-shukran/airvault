package app.lumio.data.sched

import app.lumio.data.config.Limits
import kotlin.math.ceil
import kotlin.math.min

/**
 * The token bucket and the in-flight count of one provider key: [Limits.rps] tokens a second, up to [Limits.burst] held, and no more than
 * [Limits.maxConcurrent] calls at once. Only the scheduler's lock touches it.
 */
internal class Lane(limits: Limits, nowMs: Long) {
    var limits: Limits = limits
        private set

    var inFlight = 0

    private var tokens = limits.burst.toDouble()
    private var refilledAt = nowMs

    /** Applies [next] from [nowMs] on. Tokens already earned are kept, up to the new burst. */
    fun configure(next: Limits, nowMs: Long) {
        refill(nowMs)
        limits = next
        tokens = min(tokens, next.burst.toDouble())
    }

    /** Milliseconds until a token is free: 0 when one is. */
    fun msUntilToken(nowMs: Long): Long {
        refill(nowMs)
        if (tokens >= 1.0) return 0L
        return ceil((1.0 - tokens) * 1000.0 / limits.rps).toLong()
    }

    /** Spends one token. The caller has checked that [msUntilToken] is 0. */
    fun takeToken(nowMs: Long) {
        refill(nowMs)
        tokens -= 1.0
    }

    private fun refill(nowMs: Long) {
        val elapsed = nowMs - refilledAt
        if (elapsed > 0) {
            tokens = min(limits.burst.toDouble(), tokens + elapsed * limits.rps / 1000.0)
            refilledAt = nowMs
        }
    }
}
