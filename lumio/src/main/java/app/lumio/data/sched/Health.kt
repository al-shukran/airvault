package app.lumio.data.sched

/** Where a provider's circuit stands. */
internal enum class Circuit { CLOSED, OPEN, HALF_OPEN }

/**
 * What the scheduler knows about one provider: its cooldown after a rate limit, its circuit breaker, and its health score. Only the scheduler's
 * lock touches it.
 */
internal class Health {
    var cooldownUntil = 0L
    var rateLimitStreak = 0
    var circuit = Circuit.CLOSED
    var failures = 0
    var openUntil = 0L
    var probeInFlight = false

    private var latencyMs = 0.0
    private var sampled = false
    private var errorRate = 0.0
    private val rateLimitTimes = ArrayDeque<Long>()

    /** True when the provider may be tried now: it is not cooling down, and its circuit lets a call through. */
    fun available(nowMs: Long): Boolean {
        if (nowMs < cooldownUntil || probeInFlight) return false
        return circuit != Circuit.OPEN || nowMs >= openUntil
    }

    /** Lower is healthier: the latency, plus penalties for errors and for recent rate limits. */
    fun score(nowMs: Long): Double {
        prune(nowMs)
        return latencyMs + ERROR_PENALTY_MS * errorRate + RATE_LIMIT_PENALTY_MS * rateLimitTimes.size
    }

    fun recordServed(elapsedMs: Long) {
        latencyMs = if (sampled) ALPHA * elapsedMs + (1 - ALPHA) * latencyMs else elapsedMs.toDouble()
        sampled = true
        errorRate *= 1 - ALPHA
    }

    fun recordError() {
        errorRate = ALPHA + (1 - ALPHA) * errorRate
    }

    fun recordRateLimit(nowMs: Long) {
        rateLimitTimes.addLast(nowMs)
        prune(nowMs)
    }

    private fun prune(nowMs: Long) {
        while (rateLimitTimes.isNotEmpty() && nowMs - rateLimitTimes.first() > RATE_LIMIT_WINDOW_MS) {
            rateLimitTimes.removeFirst()
        }
    }

    private companion object {
        /** The weight of the newest sample in each moving average. */
        const val ALPHA = 0.3
        const val ERROR_PENALTY_MS = 10_000.0
        const val RATE_LIMIT_PENALTY_MS = 30_000.0
        const val RATE_LIMIT_WINDOW_MS = 300_000L
    }
}
