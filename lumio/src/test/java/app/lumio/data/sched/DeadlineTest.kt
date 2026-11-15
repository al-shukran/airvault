package app.lumio.data.sched

import app.lumio.data.Clock
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.provider.Http
import app.lumio.data.provider.Op
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/** A call gives up at its priority's deadline, in virtual time: 30 s on screen, 120 s in the background. */
class DeadlineTest {

    private fun TestScope.scheduler(latencyMs: Long): Scheduler = Scheduler(
        providers = listOf(
            FakeProvider(
                "slow",
                networks = setOf(ETH),
                ops = setOf(Op.TIP_HEIGHT),
                latencyMs = latencyMs,
                now = { testScheduler.currentTime },
            ),
        ),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = Clock { testScheduler.currentTime },
        random = Random(2),
        scope = this,
    )

    @Test
    fun aForegroundCallPastThirtySecondsFailsAsTransient() = runTest {
        val scheduler = scheduler(latencyMs = 40_000L)

        assertFailsWith<ProviderError.Transient> { scheduler.run(TipHeight(ETH), Priority.FOREGROUND) }

        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun aBackgroundCallHasTheLongerDeadline() = runTest {
        val scheduler = scheduler(latencyMs = 40_000L)

        assertEquals(100L, scheduler.run(TipHeight(ETH), Priority.BACKGROUND))
    }
}
