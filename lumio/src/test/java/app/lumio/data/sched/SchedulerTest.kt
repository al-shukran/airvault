package app.lumio.data.sched

import app.lumio.data.BTC
import app.lumio.data.Clock
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import app.lumio.data.provider.Trait
import java.io.InterruptedIOException
import kotlin.random.Random
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scheduler's admission, priority, failure and circuit rules, run in virtual time. */
class SchedulerTest {

    /**
     * The wake timers run in the test scope, not in backgroundScope: advanceUntilIdle() drives only foreground work, so a timer in
     * backgroundScope would never fire inside a test that advances time that way.
     */
    private fun TestScope.scheduler(vararg providers: Provider, configs: Map<String, ProviderConfig> = emptyMap()): Scheduler =
        Scheduler(
            providers = providers.toList(),
            configs = { configs },
            http = Http(OkHttpClient()),
            clock = Clock { testScheduler.currentTime },
            random = Random(7),
            scope = this,
        )

    private fun TestScope.provider(
        id: String,
        limits: Limits = FAST,
        latencyMs: Long = 0L,
        traits: Set<Trait> = setOf(Trait.KEYLESS),
    ): FakeProvider = FakeProvider(id, traits = traits, limits = limits, latencyMs = latencyMs, now = { testScheduler.currentTime })

    /** The error that [block] throws, or null when it returns. */
    private suspend fun failureOf(block: suspend () -> Any?): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

    @Test
    fun aTokenBucketSpacesCallsAtItsRate() = runTest {
        val a = provider("a", limits = Limits(rps = 10.0, burst = 1, maxConcurrent = 10))
        val s = scheduler(a)

        repeat(3) { launch { s.run(TipHeight(ETH)) } }
        advanceUntilIdle()

        assertEquals(listOf(0L, 100L, 200L), a.callTimes)
    }

    @Test
    fun aFullBucketLetsABurstThrough() = runTest {
        val a = provider("a", limits = Limits(rps = 1.0, burst = 3, maxConcurrent = 10))
        val s = scheduler(a)

        repeat(4) { launch { s.run(TipHeight(ETH)) } }
        advanceUntilIdle()

        assertEquals(listOf(0L, 0L, 0L, 1_000L), a.callTimes)
    }

    @Test
    fun theConcurrencyCapLimitsCallsInFlight() = runTest {
        val a = provider("a", limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 2), latencyMs = 100L)
        val s = scheduler(a)

        repeat(5) { launch { s.run(TipHeight(ETH)) } }
        advanceUntilIdle()

        assertEquals(2, a.maxInFlight)
        assertEquals(listOf(0L, 0L, 100L, 100L, 200L), a.callTimes)
    }

    @Test
    fun theMostUrgentWaitingCallRunsFirst() = runTest {
        val a = provider("a", limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 1), latencyMs = 100L)
        val s = scheduler(a)
        val finished = ArrayList<String>()

        launch {
            s.run(TipHeight(ETH), Priority.BACKGROUND)
            finished.add("background-1")
        }
        launch {
            s.run(TipHeight(ETH), Priority.BACKGROUND)
            finished.add("background-2")
        }
        launch {
            s.run(TipHeight(ETH), Priority.VISIBLE)
            finished.add("visible")
        }
        launch {
            s.run(TipHeight(ETH), Priority.FOREGROUND)
            finished.add("foreground")
        }
        advanceUntilIdle()

        assertEquals(listOf("background-1", "foreground", "visible", "background-2"), finished)
    }

    @Test
    fun aCancelledWaitingCallLeavesNoSlotBehind() = runTest {
        val a = provider("a", limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 1), latencyMs = 100L)
        val s = scheduler(a)

        launch { s.run(TipHeight(ETH)) }
        val waiting = launch { s.run(TipHeight(ETH)) }
        advanceTimeBy(10)
        waiting.cancelAndJoin()
        advanceUntilIdle()

        assertEquals(100L, s.run(TipHeight(ETH)))
        assertEquals(2, a.calls)
    }

    @Test
    fun aRateLimitedProviderIsLeftAtOnceAndCooledForRetryAfter() = runTest {
        val a = provider("a").apply { outcomes.add(ProviderError.RateLimited(retryAfterMs = 5_000)) }
        val b = provider("b")
        val s = scheduler(a, b, configs = PREFER_A)

        s.run(TipHeight(ETH))
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, a.calls)
        assertEquals(1, b.calls)

        s.run(TipHeight(ETH))
        assertEquals(1, a.calls)
        assertEquals(2, b.calls)

        advanceTimeBy(4_999)
        s.run(TipHeight(ETH))
        assertEquals(1, a.calls)

        advanceTimeBy(1)
        s.run(TipHeight(ETH))
        assertEquals(2, a.calls)
    }

    @Test
    fun whenEveryProviderIsCooledACallWaitsForTheEarliestCooldown() = runTest {
        val a = provider("a").apply { outcomes.add(ProviderError.RateLimited(retryAfterMs = 5_000)) }
        val b = provider("b").apply { outcomes.add(ProviderError.RateLimited(retryAfterMs = 10_000)) }
        val s = scheduler(a, b, configs = PREFER_A)

        assertEquals(100L, s.run(TipHeight(ETH)))

        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(2, a.calls)
        assertEquals(1, b.calls)
    }

    @Test
    fun aTransientFailureMovesTheCallToTheNextCandidate() = runTest {
        val a = provider("a").apply { outcomes.add(ProviderError.Transient()) }
        val b = provider("b")
        val s = scheduler(a, b, configs = PREFER_A)

        assertEquals(100L, s.run(TipHeight(ETH)))

        assertEquals(1, a.calls)
        assertEquals(1, b.calls)
    }

    @Test
    fun aCallMakesAtMostThreeAttemptsAfterTransientFailures() = runTest {
        val a = provider("a").apply { repeat(3) { outcomes.add(ProviderError.Transient()) } }
        val s = scheduler(a)

        assertTrue(failureOf { s.run(TipHeight(ETH)) } is ProviderError.Transient)
        assertEquals(3, a.calls)
    }

    @Test
    fun theThreeAttemptsSpreadAcrossCandidates() = runTest {
        val a = provider("a").apply { repeat(5) { outcomes.add(ProviderError.Transient()) } }
        val b = provider("b").apply { repeat(5) { outcomes.add(ProviderError.Transient()) } }
        val s = scheduler(a, b, configs = PREFER_A)

        assertTrue(failureOf { s.run(TipHeight(ETH)) } is ProviderError.Transient)
        assertEquals(3, a.calls + b.calls)
    }

    @Test
    fun aTimedOutProviderIsPassedOverAtOnceAndForThirtySeconds() = runTest {
        val a = provider("a").apply { outcomes.add(ProviderError.Transient(InterruptedIOException("The call timed out."))) }
        val b = provider("b")
        val s = scheduler(a, b, configs = PREFER_A)

        assertEquals(100L, s.run(TipHeight(ETH)))
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, a.calls)
        assertEquals(1, b.calls)

        repeat(10) { s.run(TipHeight(ETH)) }
        advanceTimeBy(29_999)
        s.run(TipHeight(ETH))
        assertEquals(1, a.calls)
        assertEquals(12, b.calls)

        advanceTimeBy(1)
        s.run(TipHeight(ETH))
        assertEquals(2, a.calls)
        s.run(TipHeight(ETH))
        assertEquals(3, a.calls)
        assertEquals(12, b.calls)
    }

    @Test
    fun aProviderThatTimesOutAgainIsPassedOverForTwiceAsLong() = runTest {
        val a = provider("a").apply { repeat(2) { outcomes.add(ProviderError.Transient()) } }
        val b = provider("b")
        val s = scheduler(a, b, configs = PREFER_A)

        s.run(TipHeight(ETH))
        advanceTimeBy(30_000)
        s.run(TipHeight(ETH))
        assertEquals(2, a.calls)

        advanceTimeBy(59_999)
        s.run(TipHeight(ETH))
        assertEquals(2, a.calls)

        advanceTimeBy(1)
        s.run(TipHeight(ETH))
        assertEquals(3, a.calls)
    }

    @Test
    fun aRateLimitWithoutRetryAfterCoolsForThirtySecondsThenSixty() = runTest {
        val a = provider("a").apply { repeat(2) { outcomes.add(ProviderError.RateLimited()) } }
        val b = provider("b")
        val s = scheduler(a, b, configs = PREFER_A)

        s.run(TipHeight(ETH))
        advanceTimeBy(29_999)
        s.run(TipHeight(ETH))
        assertEquals(1, a.calls)

        advanceTimeBy(1)
        s.run(TipHeight(ETH))
        assertEquals(2, a.calls)

        advanceTimeBy(59_999)
        s.run(TipHeight(ETH))
        assertEquals(2, a.calls)

        advanceTimeBy(1)
        s.run(TipHeight(ETH))
        assertEquals(3, a.calls)
    }

    @Test
    fun fiveFailuresInARowOpenTheCircuitOfASoleProvider() = runTest {
        val a = provider("a").apply { repeat(5) { outcomes.add(ProviderError.Transient()) } }
        val s = scheduler(a)

        assertTrue(failureOf { s.run(TipHeight(ETH)) } is ProviderError.Transient)
        assertEquals(3, a.calls)
        // The fifth failure, in this call, opens the circuit. The call's last attempt then waits out the 60 s, and its probe succeeds.
        assertEquals(100L, s.run(TipHeight(ETH)))
        assertEquals(6, a.calls)
        assertTrue(testScheduler.currentTime >= 60_000L)
    }

    @Test
    fun aSoleProviderIsProbedAfterItsCircuitOpensAndRecoversOnSuccess() = runTest {
        val a = provider("a").apply { repeat(5) { outcomes.add(ProviderError.Transient()) } }
        val s = scheduler(a)
        failureOf { s.run(TipHeight(ETH)) }

        // The fifth failure, in this call, opens the circuit. The call's last attempt then waits out the 60 s, and its probe succeeds.
        assertEquals(100L, s.run(TipHeight(ETH)))
        assertEquals(6, a.calls)
        assertEquals(100L, s.run(TipHeight(ETH)))
        assertEquals(7, a.calls)
        assertEquals(0L, s.statusOf("a")?.circuitOpenMs)
    }

    @Test
    fun aProviderThatDoesNotServeTheNetworkOrTheOperationIsNeverSent() = runTest {
        val a = FakeProvider("a", networks = setOf(ETH), ops = setOf(Ops.BTC_TIP_HEIGHT))
        val s = scheduler(a)

        assertTrue(failureOf { s.run(TipHeight(BTC)) } is ProviderError.Unsupported)
        assertTrue(failureOf { s.run(NativeBalance(ETH, "0x0")) } is ProviderError.Unsupported)
        assertEquals(0, a.calls)
    }

    @Test
    fun aKeyRequiredProviderIsSkippedUntilItHasAKey() = runTest {
        val a = FakeProvider("a", traits = setOf(Trait.KEY_REQUIRED))

        assertTrue(failureOf { scheduler(a).run(TipHeight(ETH)) } is ProviderError.Unsupported)
        assertEquals(0, a.calls)

        val keyed = scheduler(a, configs = mapOf("a" to ProviderConfig(apiKey = "fake-key")))
        assertEquals(100L, keyed.run(TipHeight(ETH)))
        assertEquals(1, a.calls)
    }

    @Test
    fun aCallGivesUpAfterThreeRateLimitedAttempts() = runTest {
        // Every answer is a 429, so each attempt waits out its 1 s cooldown. A call that is refused three times gives up.
        val a = provider("a").apply { repeat(5) { outcomes.add(ProviderError.RateLimited(retryAfterMs = 1_000)) } }
        val s = scheduler(a)

        assertTrue(failureOf { s.run(TipHeight(ETH)) } is ProviderError.RateLimited)
        assertEquals(3, a.calls)
    }

    private companion object {
        val FAST = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 100)

        /** Provider "a" is tried before "b". */
        val PREFER_A = mapOf("a" to ProviderConfig(priority = 0), "b" to ProviderConfig(priority = 1))
    }
}
