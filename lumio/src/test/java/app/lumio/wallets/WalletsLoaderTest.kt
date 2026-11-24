package app.lumio.wallets

import app.lumio.chains.NativeFailure
import app.lumio.evm.ChainFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletsLoaderTest {

    @Test
    fun theChainsReadAtOnce() = runTest {
        // Each read waits until all three have started. A read that waited for another one first would never finish, and the timeout would fail the test.
        var started = 0
        val allStarted = CompletableDeferred<Unit>()
        suspend fun start() {
            if (++started == 3) allStarted.complete(Unit)
            allStarted.await()
        }
        withTimeout(1_000) {
            refreshChains(
                evm = {
                    start()
                    emptyList<ChainFailure>()
                },
                native = {
                    start()
                    emptyList<NativeFailure>()
                },
                bitcoin = { start() },
                landed = { _, _ -> },
            )
        }
    }

    @Test
    fun aSlowBitcoinChainDoesNotHoldBackTheOtherChains() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val refresh = async {
            refreshChains(
                evm = { emptyList<ChainFailure>() },
                native = { emptyList<NativeFailure>() },
                bitcoin = {
                    gate.await()
                    events += "bitcoin read"
                },
                landed = { _, _ -> events += "landed" },
            )
        }

        runCurrent()
        assertEquals(listOf("landed", "landed"), events)
        assertFalse(refresh.isCompleted)

        gate.complete(Unit)
        assertFalse(refresh.await().failed)
        assertEquals(listOf("landed", "landed", "bitcoin read", "landed"), events)
    }

    @Test
    fun aFailingChainStillLetsTheOthersLand() = runTest {
        val events = mutableListOf<String>()
        val result = refreshChains(
            evm = {
                delay(10)
                throw IllegalStateException("The Ethereum node did not answer.")
            },
            native = {
                delay(20)
                events += "native read"
                emptyList<NativeFailure>()
            },
            bitcoin = {
                delay(30)
                events += "bitcoin read"
            },
            landed = { _, _ -> events += "landed" },
        )

        assertEquals(listOf("landed", "native read", "landed", "bitcoin read", "landed"), events)
        assertTrue(result.unreadable)
    }
}
