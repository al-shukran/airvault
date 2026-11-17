package app.lumio.btc

import app.lumio.data.provider.ProviderError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The retry rule of a send's network read: a transient failure is tried once more, a refusal is not, and a broadcast is never sent twice.
 * Runs in virtual time, so the retry's wait of [RETRY_DELAY_MS] costs no real seconds.
 */
class BtcReadStepTest {
    @Test
    fun aTransientFailureIsTriedOnceMoreAndItsAnswerIsUsed() = runTest {
        var calls = 0
        val answer = readStep(BtcReadStep.UNSPENT_OUTPUTS) {
            calls++
            if (calls == 1) throw ProviderError.Transient() else 5
        }

        assertEquals(5, answer)
        assertEquals(2, calls)
    }

    @Test
    fun aRefusalIsNotRetriedAndNamesItsStep() = runTest {
        var calls = 0
        val error = assertFailsWith<BtcReadException> {
            readStep(BtcReadStep.PREVIOUS_TRANSACTIONS) {
                calls++
                throw ProviderError.Fatal("The provider rejected the request.")
            }
        }

        assertEquals(1, calls)
        assertEquals(BtcReadStep.PREVIOUS_TRANSACTIONS, error.step)
        assertTrue(error.message.orEmpty().contains("reading the transactions that the send spends"), error.message)
        assertTrue(error.message.orEmpty().endsWith("The provider rejected the request. Try again."), error.message)
    }

    @Test
    fun aTransientFailureOfABroadcastIsNotRetried() = runTest {
        var calls = 0
        val error = assertFailsWith<BtcReadException> {
            readStep(BtcReadStep.BROADCAST, retry = false) {
                calls++
                throw ProviderError.Transient()
            }
        }

        assertEquals(1, calls, "a relayed transaction must not be posted twice")
        assertEquals(BtcReadStep.BROADCAST, error.step)
    }
}
