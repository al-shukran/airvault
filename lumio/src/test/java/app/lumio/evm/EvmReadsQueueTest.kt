package app.lumio.evm

import app.lumio.data.Clock
import app.lumio.data.FakeProvider
import app.lumio.data.config.Limits
import app.lumio.data.provider.Http
import app.lumio.data.sched.Scheduler
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/** A read's wait in the scheduler's queue is not cut short by a deadline of [EvmReads]' own: the scheduler's deadline covers only the time a request is sent. */
class EvmReadsQueueTest {

    private val chain = EvmChain(chainId = 1, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)

    private fun TestScope.reads(provider: FakeProvider): EvmReads {
        val clock = Clock { testScheduler.currentTime }
        val http = Http(OkHttpClient())
        return EvmReads(
            pool = Scheduler(listOf(provider), { emptyMap() }, http, clock, Random(3), this),
            newOwner = { Scheduler(emptyList(), { emptyMap() }, http, clock, Random(3), this) },
            settings = { EvmSettings() },
        )
    }

    @Test
    fun aReadQueuedLongerThanThirtySecondsStillAnswers() = runTest {
        // One request at a time, 20 s each: the third read waits 40 s in the queue, then takes 20 s once it is sent.
        val a = FakeProvider(
            "a",
            limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 1),
            latencyMs = 20_000L,
            now = { testScheduler.currentTime },
        )
        val reads = reads(a)
        val answers = ArrayList<BigInteger>()

        repeat(3) { launch { answers += reads.balance(chain, ADDRESS) } }
        advanceUntilIdle()

        assertEquals(3, answers.size)
        assertEquals(listOf(0L, 20_000L, 40_000L), a.callTimes)
    }

    private companion object {
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
    }
}
