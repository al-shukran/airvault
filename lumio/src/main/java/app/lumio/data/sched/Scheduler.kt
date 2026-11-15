package app.lumio.data.sched

import app.lumio.data.Clock
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.CacheUse
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.route.ProviderState
import app.lumio.data.route.Router
import app.lumio.data.route.isEligible
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/**
 * How urgent a provider call is. When a slot frees, the most urgent waiting call takes it. [deadlineMs] is how long a call of this priority may take
 * in all, waits included, before it fails as [ProviderError.Transient]. A call on screen gives up sooner than a background scan.
 */
@Serializable
enum class Priority(internal val rank: Int, val deadlineMs: Long) {
    FOREGROUND(0, 30_000L),
    VISIBLE(1, 30_000L),
    BACKGROUND(2, 120_000L),
}

/**
 * The one scheduler for provider calls. [run] checks the cache, routes the request, and admits it through the provider's token bucket and
 * concurrency cap. Failures are handled as follows:
 * - [ProviderError.RateLimited] cools the provider, for its Retry-After or else an exponential backoff with full jitter (base 1 s, cap 5 min).
 *   The call moves to the next candidate at once. When every candidate is cooling, the call waits for the earliest cooldown.
 * - [ProviderError.Transient], and an [IOException], move the call to the next candidate after a jittered backoff. A call makes at most three
 *   attempts that fail this way.
 * - Five transient failures in a row open the provider's circuit for 60 s. Then one probe call goes out. A successful probe closes the circuit,
 *   and a failed one opens it again.
 * - [ProviderError.Unsupported] and [ProviderError.Fatal] skip the provider for that call only. [ProviderError.NotFound] is an answer.
 *
 * Each call has a deadline set by its [Priority], and one that runs past it fails as [ProviderError.Transient]. A provider that sends more than one HTTP
 * request in a call pays for each request after the first from the same token bucket (see [Http.charge]), so its requests stay within the rate.
 *
 * Time comes from [clock] and jitter from [random], so tests run in virtual time. [scope] runs the timers that wake waiting calls. [cache] is read
 * for the open vault, and it is null while the vault is locked, when only uncached calls can run.
 */
class Scheduler(
    providers: List<Provider>,
    private val configs: () -> Map<String, ProviderConfig>,
    private val http: Http,
    private val clock: Clock,
    private val random: Random,
    private val scope: CoroutineScope,
    private val cache: () -> Cache? = { null },
) {
    private val all: List<Provider> = providers.toList()
    private val router = Router(all)
    private val mutex = Mutex()
    private val lanes = HashMap<Pair<String, String?>, Lane>()
    private val health = HashMap<String, Health>()
    private val waiters = ArrayList<Waiter>()
    private var nextSeq = 0L
    private var wakeGen = 0L

    private val state = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = health[providerId]?.available(nowMs) ?: true

        override fun score(providerId: String, nowMs: Long): Double = health[providerId]?.score(nowMs) ?: 0.0
    }

    init {
        require(all.map { it.id }.toSet().size == all.size) { "Provider ids must be unique." }
    }

    /**
     * What this scheduler knows about [providerId] now, or null when it does not hold that provider. The lock keeps the read from racing the calls,
     * so the settings screen may call it from any coroutine.
     */
    suspend fun statusOf(providerId: String): ProviderHealth? {
        if (all.none { it.id == providerId }) return null
        return mutex.withLock {
            val now = clock.nowMs()
            health[providerId]?.snapshot(now) ?: ProviderHealth(score = null, coolingMs = 0L, circuitOpenMs = 0L)
        }
    }

    /**
     * Serves [req] at [priority]. With [cacheUse] and an open cache, a stored value is returned without a call, and a value that a provider returns
     * is stored when [CacheUse.keep] accepts it. Without a cache the request is simply sent.
     *
     * @throws ProviderError.Unsupported when no enabled provider can serve the request. Otherwise it throws the error of the last candidate, and
     * [ProviderError.Transient] when the call passes its deadline.
     */
    suspend fun <T> run(req: Request<T>, priority: Priority = Priority.VISIBLE, cacheUse: CacheUse<T>? = null): T {
        val store = if (cacheUse == null) null else cache()
        if (cacheUse != null && store != null && cacheUse.policy !is CachePolicy.LastKnown) {
            store.get(cacheUse.policy, cacheUse.key)?.let { entry ->
                // A stored value that does not decode is a miss: the call is made again and the value replaced.
                runCatching { cacheUse.codec.decode(entry.value) }.onSuccess { return it }
            }
        }
        val value = try {
            withTimeout(priority.deadlineMs) { call(req, priority) }
        } catch (e: TimeoutCancellationException) {
            throw ProviderError.Transient(e)
        }
        if (cacheUse != null && store != null && cacheUse.keep(value)) {
            store.put(cacheUse.policy, cacheUse.key, cacheUse.codec.encode(value))
        }
        return value
    }

    private suspend fun <T> call(req: Request<T>, priority: Priority): T {
        val tried = HashSet<String>()
        val excluded = HashSet<String>()
        var transientFailures = 0
        var lastError: ProviderError? = null
        while (true) {
            val grant = acquire(req, priority, excluded, tried, lastError)
            val started = clock.nowMs()
            val result = try {
                Result.success(grant.provider.execute(req, grant.cfg, Http(http.client) { takeExtraToken(grant.lane) }))
            } catch (e: ProviderError) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(ProviderError.Transient(e))
            } catch (e: Throwable) {
                settle(grant, Outcome.Abandoned)
                throw e
            }
            val elapsed = clock.nowMs() - started
            if (result.isSuccess) {
                settle(grant, Outcome.Served(elapsed))
                return result.getOrThrow()
            }
            val failure = result.exceptionOrNull() as ProviderError
            if (failure is ProviderError.NotFound) {
                settle(grant, Outcome.Served(elapsed))
                throw failure
            }
            settle(grant, Outcome.Failed(failure))
            lastError = failure
            when (failure) {
                is ProviderError.RateLimited -> Unit
                is ProviderError.Transient -> {
                    tried.add(grant.provider.id)
                    transientFailures++
                    if (transientFailures >= MAX_ATTEMPTS) throw failure
                    delay(jitterMs(backoffCeiling(transientFailures)))
                }
                is ProviderError.Unsupported, is ProviderError.Fatal -> excluded.add(grant.provider.id)
                is ProviderError.NotFound -> throw failure
            }
        }
    }

    /** Waits until a provider can take the call. Throws the last error, or [ProviderError.Unsupported], when none can serve it. */
    private suspend fun acquire(
        req: Request<*>,
        priority: Priority,
        excluded: Set<String>,
        tried: Set<String>,
        lastError: ProviderError?,
    ): Grant {
        val waiter = mutex.withLock {
            Waiter(priority, nextSeq++, req.net, req.op, excluded.toSet(), tried.toSet()).also {
                waiters.add(it)
                scheduleWakeLocked(pumpLocked(clock.nowMs()))
            }
        }
        val grant = try {
            waiter.wake.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { abandon(waiter) }
            throw e
        }
        return grant ?: throw (lastError ?: ProviderError.Unsupported())
    }

    /** Takes back a call that was cancelled while it waited. A slot it was already given is handed on. */
    private suspend fun abandon(waiter: Waiter) {
        mutex.withLock {
            val now = clock.nowMs()
            if (waiters.remove(waiter)) {
                scheduleWakeLocked(pumpLocked(now))
            } else {
                waiter.granted?.let { settleLocked(it, Outcome.Abandoned, now) }
            }
        }
    }

    /** Gives the call's slot back and records how the call went. */
    private suspend fun settle(grant: Grant, outcome: Outcome) {
        withContext(NonCancellable) {
            mutex.withLock { settleLocked(grant, outcome, clock.nowMs()) }
        }
    }

    private fun settleLocked(grant: Grant, outcome: Outcome, now: Long) {
        grant.lane.inFlight--
        val h = health.getOrPut(grant.provider.id) { Health() }
        if (grant.probe) h.probeInFlight = false
        when (outcome) {
            is Outcome.Served -> {
                h.recordServed(outcome.latencyMs)
                h.failures = 0
                h.rateLimitStreak = 0
                h.circuit = Circuit.CLOSED
            }
            is Outcome.Failed -> onFailureLocked(h, outcome.error, now)
            Outcome.Abandoned -> Unit
        }
        scheduleWakeLocked(pumpLocked(now))
    }

    private fun onFailureLocked(h: Health, error: ProviderError, now: Long) {
        when (error) {
            is ProviderError.RateLimited -> {
                h.recordRateLimit(now)
                val wait = error.retryAfterMs ?: nextRateLimitWaitLocked(h)
                h.cooldownUntil = now + maxOf(1L, wait)
            }
            is ProviderError.Transient -> {
                h.recordError()
                h.failures++
                if (h.circuit == Circuit.HALF_OPEN || h.failures >= FAILURE_THRESHOLD) trip(h, now)
            }
            is ProviderError.Fatal -> h.recordError()
            is ProviderError.Unsupported, is ProviderError.NotFound -> Unit
        }
    }

    private fun nextRateLimitWaitLocked(h: Health): Long {
        h.rateLimitStreak++
        return random.nextLong(backoffCeiling(h.rateLimitStreak) + 1)
    }

    private fun trip(h: Health, now: Long) {
        h.circuit = Circuit.OPEN
        h.openUntil = now + OPEN_MS
        h.failures = 0
    }

    /**
     * Gives every waiting call what it can take, most urgent first, and returns the next time a wait could end, or null when only a release
     * can help. A call that no provider can ever serve is completed with null.
     */
    private fun pumpLocked(now: Long): Long? {
        var wakeAt: Long? = null
        for (w in waiters.sortedWith(compareBy<Waiter>({ it.priority.rank }, { it.seq }))) {
            when (val attempt = tryGrantLocked(w.net, w.op, w.excluded, w.tried, now)) {
                is Attempt.Granted -> {
                    waiters.remove(w)
                    w.granted = attempt.grant
                    w.wake.complete(attempt.grant)
                }
                Attempt.Unserved -> {
                    waiters.remove(w)
                    w.wake.complete(null)
                }
                is Attempt.Waiting -> {
                    val at = attempt.wakeAt
                    if (at != null) wakeAt = wakeAt?.let { minOf(it, at) } ?: at
                }
            }
        }
        return wakeAt
    }

    private fun tryGrantLocked(net: NetworkId, op: Op, excluded: Set<String>, tried: Set<String>, now: Long): Attempt {
        val current = configs()
        val candidates = router.route(net, op, current, state, now, tried, excluded)
        if (candidates.isEmpty()) {
            if (all.none { it.id !in excluded && isEligible(it, net, op, current) }) return Attempt.Unserved
            return Attempt.Waiting(recoveryLocked(net, op, current, excluded, now))
        }
        var wakeAt: Long? = null
        for (p in candidates) {
            val cfg = current[p.id] ?: ProviderConfig()
            val limits = cfg.limits ?: p.defaultLimits(cfg)
            val lane = lanes.getOrPut(p.id to cfg.apiKey) { Lane(limits, now) }
            lane.configure(limits, now)
            if (lane.inFlight >= limits.maxConcurrent) continue
            val wait = lane.msUntilToken(now)
            if (wait > 0) {
                val at = now + wait
                wakeAt = wakeAt?.let { minOf(it, at) } ?: at
                continue
            }
            lane.takeToken(now)
            lane.inFlight++
            val h = health.getOrPut(p.id) { Health() }
            if (h.circuit == Circuit.OPEN && now >= h.openUntil) h.circuit = Circuit.HALF_OPEN
            val probe = h.circuit == Circuit.HALF_OPEN
            if (probe) h.probeInFlight = true
            return Attempt.Granted(Grant(p, cfg, lane, probe))
        }
        return Attempt.Waiting(wakeAt)
    }

    /** The earliest time a cooling or open provider that could serve the request becomes available again, or null when none will by itself. */
    private fun recoveryLocked(net: NetworkId, op: Op, current: Map<String, ProviderConfig>, excluded: Set<String>, now: Long): Long? {
        var earliest: Long? = null
        for (p in all) {
            if (p.id in excluded || !isEligible(p, net, op, current)) continue
            val h = health[p.id] ?: continue
            val times = listOf(h.cooldownUntil, if (h.circuit == Circuit.OPEN) h.openUntil else 0L)
            for (t in times) {
                if (t > now) earliest = earliest?.let { minOf(it, t) } ?: t
            }
        }
        return earliest
    }

    /** Arranges for [pumpLocked] to run at [wakeAt]. A timer that has been superseded does nothing when it fires. */
    private fun scheduleWakeLocked(wakeAt: Long?) {
        val generation = ++wakeGen
        if (wakeAt == null) return
        scope.launch {
            delay((wakeAt - clock.nowMs()).coerceAtLeast(1L))
            mutex.withLock {
                if (generation == wakeGen) scheduleWakeLocked(pumpLocked(clock.nowMs()))
            }
        }
    }

    private suspend fun jitterMs(ceiling: Long): Long = mutex.withLock { random.nextLong(ceiling + 1) }

    /**
     * Takes one token from [lane] for a request beyond the first of a call, and waits until one is free. The call keeps its slot while it waits, and
     * its deadline covers the wait.
     */
    private suspend fun takeExtraToken(lane: Lane) {
        while (true) {
            val waitMs = mutex.withLock {
                val now = clock.nowMs()
                val wait = lane.msUntilToken(now)
                if (wait == 0L) lane.takeToken(now)
                wait
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    /** The jitter ceiling for attempt [n], counted from 1: the base doubled each time, up to the cap. */
    private fun backoffCeiling(n: Int): Long = minOf(CAP_MS, BASE_MS shl (n - 1).coerceIn(0, MAX_DOUBLINGS))

    private class Grant(val provider: Provider, val cfg: ProviderConfig, val lane: Lane, val probe: Boolean)

    private class Waiter(
        val priority: Priority,
        val seq: Long,
        val net: NetworkId,
        val op: Op,
        val excluded: Set<String>,
        val tried: Set<String>,
    ) {
        /** Completed with the grant, or with null when no provider can serve the call. */
        val wake = CompletableDeferred<Grant?>()

        /** The slot given to this call, kept so a call cancelled after the grant can hand it back. */
        var granted: Grant? = null
    }

    private sealed interface Attempt {
        class Granted(val grant: Grant) : Attempt

        /** Nothing can be granted now. [wakeAt] is when a wait could end, or null when only a release can help. */
        class Waiting(val wakeAt: Long?) : Attempt

        data object Unserved : Attempt
    }

    private sealed interface Outcome {
        class Served(val latencyMs: Long) : Outcome

        class Failed(val error: ProviderError) : Outcome

        data object Abandoned : Outcome
    }

    private companion object {
        const val BASE_MS = 1_000L
        const val CAP_MS = 300_000L
        const val MAX_DOUBLINGS = 20
        const val MAX_ATTEMPTS = 3
        const val FAILURE_THRESHOLD = 5
        const val OPEN_MS = 60_000L
    }
}
