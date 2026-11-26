package app.lumio.send

import app.lumio.data.provider.NotSent
import app.lumio.data.provider.ProviderError
import app.lumio.evm.RpcFailure
import java.io.IOException
import java.net.ConnectException // CI_ALLOW_NETWORK_REFERENCE: exception type only, used as a fixture; no network access
import java.net.SocketTimeoutException // CI_ALLOW_NETWORK_REFERENCE: exception type only, used as a fixture; no network access
import java.net.UnknownHostException // CI_ALLOW_NETWORK_REFERENCE: exception type only, used as a fixture; no network access
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun aFailureKeepsItsTypeAndOnlyTheMessagesThatAreLumiosOwn() = runTest {
        val secret = Exception("https://rpc.example/key-123")
        val harness = Harness(listOf { throw IllegalArgumentException("fee too low", secret) })
        val machine = harness.build(byteArrayOf(1))
        machine.submit()
        val failed = assertIs<SubmitState.Failed>(machine.state.value)
        assertEquals("IllegalArgumentException: fee too low\nException", failed.detail)
    }

    @Test
    fun aFailureAfterWhichTheNetworkMayHoldTheTransactionIsUnknown() = runTest {
        val timeout = Harness(listOf { throw ProviderError.Transient(SocketTimeoutException("timeout")) }).build(byteArrayOf(1))
        timeout.submit()
        assertTrue(assertIs<SubmitState.Failed>(timeout.state.value).unknown)

        val unreadable = Harness(listOf { throw ProviderError.Unreadable("The endpoint sent no result.") }).build(byteArrayOf(1))
        unreadable.submit()
        assertTrue(assertIs<SubmitState.Failed>(unreadable.state.value).unknown)

        val wrapped = Harness(listOf { throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.", ProviderError.Transient()) }).build(byteArrayOf(1))
        wrapped.submit()
        assertTrue(assertIs<SubmitState.Failed>(wrapped.state.value).unknown)
    }

    @Test
    fun aFailureMarkedNotSentIsNotUnknownWhateverItCarries() = runTest {
        val read = ProviderError.Transient(NotSent("The block height was not read.", ProviderError.Transient(SocketTimeoutException("timeout"))))
        val machine = Harness(listOf { throw read }).build(byteArrayOf(1))
        machine.submit()
        assertFalse(assertIs<SubmitState.Failed>(machine.state.value).unknown)
    }

    @Test
    fun aFailureThatNeverLeftIsNotUnknown() = runTest {
        val offline = Harness(listOf { throw ProviderError.Transient(UnknownHostException("rpc.example")) }).build(byteArrayOf(1))
        offline.submit()
        assertFalse(assertIs<SubmitState.Failed>(offline.state.value).unknown)

        val hidden = ConnectException("Failed to connect").apply { addSuppressed(IOException("unexpected end of stream")) }
        val resent = Harness(listOf { throw ProviderError.Transient(hidden) }).build(byteArrayOf(1))
        resent.submit()
        assertTrue(assertIs<SubmitState.Failed>(resent.state.value).unknown)
    }

    @Test
    fun aRefusalIsNotUnknown() = runTest {
        for (refusal in listOf<Exception>(ProviderError.Fatal("refused"), ProviderError.RateLimited(), Refused("insufficient fee"))) {
            val machine = Harness(listOf { throw refusal }).build(byteArrayOf(1))
            machine.submit()
            assertFalse(assertIs<SubmitState.Failed>(machine.state.value).unknown)
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

    @Test
    fun theSignedTimeIsTakenWhenTheMachineIsMadeAndTheSentTimeWhenTheNetworkAccepts() = runTest {
        val moments = ArrayDeque(listOf(1_000L, 2_500L))
        val machine = SubmitMachine<ByteArray, String>(
            signed = byteArrayOf(7),
            broadcast = { "txid" },
            describe = { SubmitFailure(it.message.orEmpty()) },
            clock = { moments.removeFirst() },
        )
        assertEquals(1_000L, machine.signedAtMs)
        machine.submit()
        assertEquals(2_500L, assertIs<SubmitState.Sent<String>>(machine.state.value).sentAtMs)
    }
}
