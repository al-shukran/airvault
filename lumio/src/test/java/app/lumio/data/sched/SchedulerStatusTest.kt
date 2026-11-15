package app.lumio.data.sched

import app.lumio.data.Clock
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** What the settings screen reads from a scheduler: whether it holds a provider, and that provider's cooldown and score, in virtual time. */
class SchedulerStatusTest {

    private fun TestScope.scheduler(vararg providers: FakeProvider): Scheduler = Scheduler(
        providers = providers.toList(),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
        random = Random(7),
        scope = this,
    )

    @Test
    fun aProviderTheSchedulerDoesNotHoldHasNoStatus() = runTest {
        assertNull(scheduler(FakeProvider("a")).statusOf("missing"))
    }

    @Test
    fun aProviderNoCallHasReachedHasNoScore() = runTest {
        assertEquals(ProviderHealth(score = null, coolingMs = 0L, circuitOpenMs = 0L), scheduler(FakeProvider("a")).statusOf("a"))
    }

    @Test
    fun aServedCallGivesTheProviderAScore() = runTest {
        val s = scheduler(FakeProvider("a"))

        s.run(TipHeight(ETH))

        val status = s.statusOf("a")
        assertNotNull(status?.score)
        assertEquals(0L, status?.coolingMs)
    }

    @Test
    fun aRateLimitShowsAsACooldownUntilItEnds() = runTest {
        val a = FakeProvider("a")
        a.outcomes.add(ProviderError.RateLimited(retryAfterMs = 5_000L))
        val s = scheduler(a)
        val call = launch { s.run(TipHeight(ETH)) }

        advanceTimeBy(1_000L)
        assertEquals(4_000L, s.statusOf("a")?.coolingMs)

        advanceUntilIdle()
        assertEquals(0L, s.statusOf("a")?.coolingMs)
        call.join()
    }
}
