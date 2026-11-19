package app.lumio.data.sched

import app.lumio.data.Clock
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.Http
import app.lumio.data.provider.Ops
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Trait
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A history call is routed like any other call: a rate limit moves it to the next provider at once, and a provider that is cooling is not asked again
 * until its Retry-After has passed. Time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TxHistoryRoutingTest {
    private val account = "0x" + "ab".repeat(20)
    private val limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 100)

    private fun TestScope.provider(id: String): FakeProvider =
        FakeProvider(id, traits = setOf(Trait.KEYLESS), ops = setOf(Ops.EVM_TX_HISTORY), limits = limits, now = { testScheduler.currentTime })

    /** The routescan provider ranks first (priority 0), and blockscout is the fallback (priority 1). */
    private fun TestScope.scheduler(first: FakeProvider, second: FakeProvider): Scheduler = Scheduler(
        providers = listOf(first, second),
        configs = { mapOf(first.id to ProviderConfig(priority = 0), second.id to ProviderConfig(priority = 1)) },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
        random = Random(7),
        scope = this,
    )

    @Test
    fun aRateLimitedHistoryMovesToTheNextProviderAtOnce() = runTest {
        val routescan = provider("routescan").apply { outcomes.add(ProviderError.RateLimited(30_000L)) }
        val blockscout = provider("blockscout")
        val scheduler = scheduler(routescan, blockscout)

        scheduler.run(AddressTxHistory(ETH, account, 1, 20))

        assertEquals(1, routescan.calls)
        assertEquals(1, blockscout.calls)
    }

    @Test
    fun aCoolingProviderIsNotAskedAgainUntilItsRetryAfterHasPassed() = runTest {
        val routescan = provider("routescan").apply { outcomes.add(ProviderError.RateLimited(30_000L)) }
        val blockscout = provider("blockscout")
        val scheduler = scheduler(routescan, blockscout)

        scheduler.run(AddressTxHistory(ETH, account, 1, 20))
        scheduler.run(AddressTxHistory(ETH, account, 1, 20))

        assertEquals(1, routescan.calls)
        assertEquals(2, blockscout.calls)

        advanceTimeBy(30_000L)
        scheduler.run(AddressTxHistory(ETH, account, 1, 20))

        assertEquals(2, routescan.calls)
    }
}
