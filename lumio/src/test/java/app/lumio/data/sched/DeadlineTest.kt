package app.lumio.data.sched

import app.lumio.data.Clock
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.config.Limits
import app.lumio.data.provider.Http
import app.lumio.data.provider.Ops
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/** A request has a deadline set by its priority, counted from the moment it is sent, in virtual time: 30 s on screen, 120 s in the background. */
class DeadlineTest {

    private fun TestScope.provider(latencyMs: Long, maxConcurrent: Int = 100): FakeProvider = FakeProvider(
        "slow",
        networks = setOf(ETH),
        ops = setOf(Ops.BTC_TIP_HEIGHT),
        limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = maxConcurrent),
        latencyMs = latencyMs,
        now = { testScheduler.currentTime },
    )

    private fun TestScope.scheduler(provider: FakeProvider): Scheduler = Scheduler(
        providers = listOf(provider),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
        random = Random(2),
        scope = this,
    )

    @Test
    fun aRequestThatRunsPastItsDeadlineFailsAsTransientAfterThreeAttempts() = runTest {
        val slow = provider(latencyMs = 40_000L)
        val scheduler = scheduler(slow)

        assertFailsWith<ProviderError.Transient> { scheduler.run(TipHeight(ETH), Priority.FOREGROUND) }

        assertEquals(3, slow.calls, "each attempt is cut at 30 s, and the third failure ends the call")
        assertEquals(0L, slow.callTimes[0])
        assertTrue(slow.callTimes[1] >= 30_000L, "the second attempt is sent once the first is cut at 30 s")
    }

    @Test
    fun aBackgroundCallHasTheLongerDeadline() = runTest {
        val scheduler = scheduler(provider(latencyMs = 40_000L))

        assertEquals(100L, scheduler.run(TipHeight(ETH), Priority.BACKGROUND))
    }

    @Test
    fun aRequestQueuedLongerThanItsDeadlineStillSucceedsOnceItIsSent() = runTest {
        // One request at a time, 20 s each: the third waits 40 s in the queue, past the 30 s deadline, then takes 20 s once it is sent.
        val slow = provider(latencyMs = 20_000L, maxConcurrent = 1)
        val scheduler = scheduler(slow)
        val answers = mutableListOf<Long>()

        repeat(3) { launch { answers += scheduler.run(TipHeight(ETH), Priority.VISIBLE) } }
        advanceUntilIdle()

        assertEquals(listOf(100L, 100L, 100L), answers)
        assertEquals(listOf(0L, 20_000L, 40_000L), slow.callTimes)
        assertEquals(60_000L, testScheduler.currentTime)
    }
}
