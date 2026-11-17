package app.lumio.send

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SubmitMachineTest {
    private class Refused(message: String) : Exception(message)

    /** A machine whose broadcast answers each call from [answers] in turn, and records the bytes of every call. */
    private class Harness(private val answers: List<() -> String>) {
        val sent = mutableListOf<ByteArray>()
        val statesAtBroadcast = mutableListOf<SubmitState<String>>()
        lateinit var machine: SubmitMachine<ByteArray, String>
        private var next = 0

        fun build(signed: ByteArray, describe: (Throwable) -> SubmitFailure = { SubmitFailure(it.message.orEmpty()) }): SubmitMachine<ByteArray, String> {
            machine = SubmitMachine(
                signed = signed,
                broadcast = { bytes ->
                    sent += bytes
                    statesAtBroadcast += machine.state.value
                    answers[next++]()
                },
                describe = describe,
            )
            return machine
        }
    }

    @Test
    fun nothingIsBroadcastUntilSubmit() = runTest {
        val harness = Harness(listOf { "txid" })
        val machine = harness.build(byteArrayOf(1, 2, 3))
        assertIs<SubmitState.Idle>(machine.state.value)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun idleThenSubmittingThenSent() = runTest {
        val harness = Harness(listOf { "txid-1" })
        val machine = harness.build(byteArrayOf(1, 2, 3))
        machine.submit()
        assertIs<SubmitState.Submitting>(harness.statesAtBroadcast.single())
        val sent = machine.state.value
        assertIs<SubmitState.Sent<String>>(sent)
        assertEquals("txid-1", sent.id)
    }

    @Test
    fun refusalFailsWithTheNetworksWordsAndRetryReusesTheSameBytes() = runTest {
        val harness = Harness(listOf({ throw Refused("insufficient fee") }, { "txid-2" }))
        val signed = byteArrayOf(9, 8, 7)
        val machine = harness.build(signed)
        machine.submit()
        val failed = machine.state.value
        assertIs<SubmitState.Failed>(failed)
        assertEquals("insufficient fee", failed.message)
        assertTrue(failed.retryable)

        machine.submit()
        assertIs<SubmitState.Sent<String>>(machine.state.value)
        assertEquals(2, harness.sent.size)
        assertSame(signed, harness.sent[0])
        assertSame(signed, harness.sent[1])
    }

    @Test
    fun aRefusalThatCanNeverSucceedOffersNoRetry() = runTest {
        val harness = Harness(listOf({ throw Refused("too old") }, { "never" }))
        val machine = harness.build(byteArrayOf(4), describe = { SubmitFailure("too old", retryable = false) })
        machine.submit()
        val failed = machine.state.value
        assertIs<SubmitState.Failed>(failed)
        assertEquals(false, failed.retryable)

        machine.submit()
        assertEquals(1, harness.sent.size)
        assertIs<SubmitState.Failed>(machine.state.value)
    }

    @Test
    fun submittingAgainAfterSentBroadcastsNothing() = runTest {
        val harness = Harness(listOf { "txid-3" })
        val machine = harness.build(byteArrayOf(5))
        machine.submit()
        machine.submit()
        assertEquals(1, harness.sent.size)
        assertIs<SubmitState.Sent<String>>(machine.state.value)
    }

    @Test
    fun aCancelledSubmitIsNotTurnedIntoAFailure() = runTest {
        val harness = Harness(listOf({ throw CancellationException("left the screen") }))
        val machine = harness.build(byteArrayOf(6))
        assertFailsWith<CancellationException> { machine.submit() }
        assertIs<SubmitState.Submitting>(machine.state.value)
    }
}
