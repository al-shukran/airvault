package app.lumio.data.sched

import app.lumio.data.ETH
import app.lumio.data.FakeClock
import app.lumio.data.FakeProvider
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Trait
import java.math.BigInteger
import kotlin.random.Random
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** The scheduler's calls over the router's rules: broadcasts stay on MEV-protected providers, and a fallback-only provider answers last. */
class RoutingTest {

    private fun TestScope.scheduler(vararg providers: Provider): Scheduler = Scheduler(
        providers = providers.toList(),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = FakeClock(),
        random = Random(7),
        scope = this,
    )

    @Test
    fun aBroadcastGoesOnlyToTheMevProtectedProviderWhileItIsHealthy() = runTest {
        val plain = FakeProvider("plain", ops = setOf(Ops.EVM_BROADCAST))
        val protectedRpc = FakeProvider("protected", ops = setOf(Ops.EVM_BROADCAST), traits = setOf(Trait.KEYLESS, Trait.MEV_PROTECTED))

        scheduler(plain, protectedRpc).run(Broadcast(ETH, byteArrayOf(2)))

        assertEquals(1, protectedRpc.calls)
        assertEquals(0, plain.calls)
    }

    @Test
    fun aBroadcastGoesToAnotherProviderOnlyWhileTheMevProviderIsCoolingDown() = runTest {
        val plain = FakeProvider("plain", ops = setOf(Ops.EVM_BROADCAST))
        val protectedRpc = FakeProvider("protected", ops = setOf(Ops.EVM_BROADCAST), traits = setOf(Trait.KEYLESS, Trait.MEV_PROTECTED))
        protectedRpc.outcomes.add(ProviderError.RateLimited(retryAfterMs = 60_000L))

        assertEquals("0xtx", scheduler(plain, protectedRpc).run(Broadcast(ETH, byteArrayOf(2))))

        assertEquals(1, protectedRpc.calls)
        assertEquals(1, plain.calls)
    }

    @Test
    fun aFallbackOnlyProviderAnswersOnlyWhenTheOthersAreCoolingDown() = runTest {
        val others = FakeProvider("others", ops = setOf(Ops.EVM_NATIVE_BALANCE))
        val tenderly = FakeProvider("tenderly", ops = setOf(Ops.EVM_NATIVE_BALANCE), traits = setOf(Trait.KEYLESS, Trait.FALLBACK_ONLY))
        val s = scheduler(others, tenderly)
        val address = "0x" + "11".repeat(20)

        assertEquals(BigInteger.ONE, s.run(NativeBalance(ETH, address)))
        assertEquals(0, tenderly.calls)

        others.outcomes.add(ProviderError.RateLimited(retryAfterMs = 60_000L))
        assertEquals(BigInteger.ONE, s.run(NativeBalance(ETH, address)))
        assertEquals(1, tenderly.calls)
    }
}
