package app.lumio.tokens

import app.lumio.evm.RpcFailure
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The triggers of an Add Token lookup: a valid address starts it once, a new address cancels the old one, and an endpoint that never answers fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedAddressLookupTest {
    private val pepeAddress = "0x6982508145454ce325ddbe47a25d4ec3d2311933"
    private val usdcAddress = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val pepe = Token(1L, pepeAddress, "PEPE", "Pepe", 18)

    private val states = mutableListOf<TypedAddressLookup.State>()

    private fun lastDone(): Result<Token> = (states.last() as TypedAddressLookup.State.Done).result

    @Test
    fun aPastedValidAddressTriggersExactlyOneLookupEvenWhenTheOwnerLeavesTheField() = runTest {
        val reads = mutableListOf<String>()
        val lookup = TypedAddressLookup(this, read = { reads += it; pepe }, onState = { states += it })

        lookup.changed(pepeAddress)
        lookup.commit()
        advanceUntilIdle()
        lookup.commit()
        advanceUntilIdle()

        assertEquals(listOf(pepeAddress), reads)
        assertEquals(pepe, lastDone().getOrNull())
    }

    @Test
    fun editingToAnotherValidAddressCancelsTheFirstLookup() = runTest {
        val reads = mutableListOf<String>()
        var firstCancelled = false
        val lookup = TypedAddressLookup(
            scope = this,
            read = { contract ->
                reads += contract
                if (contract == usdcAddress) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        firstCancelled = true
                        throw e
                    }
                }
                pepe
            },
            onState = { states += it },
        )

        lookup.changed(usdcAddress)
        advanceUntilIdle()
        assertEquals(listOf(usdcAddress), reads)

        lookup.changed(pepeAddress)
        advanceUntilIdle()

        assertTrue(firstCancelled)
        assertEquals(listOf(usdcAddress, pepeAddress), reads)
        assertEquals(pepe, lastDone().getOrNull())
    }

    @Test
    fun aLookupThatDoesNotAnswerInTimeFailsAsUnreachable() = runTest {
        val lookup = TypedAddressLookup(this, read = { awaitCancellation() }, onState = { states += it })

        lookup.changed(pepeAddress)
        advanceUntilIdle()

        val failure = lastDone().exceptionOrNull()
        assertIs<RpcFailure>(failure)
        assertEquals(RpcFailure.Reason.UNREACHABLE, failure.reason)
        assertEquals("The endpoint did not answer.", failure.message)
    }

    @Test
    fun leavingTheFieldTriesAnUnreachableAddressAgain() = runTest {
        var calls = 0
        val lookup = TypedAddressLookup(
            scope = this,
            read = { calls++; if (calls == 1) awaitCancellation() else pepe },
            onState = { states += it },
        )

        lookup.changed(pepeAddress)
        advanceUntilIdle()
        lookup.commit()
        advanceUntilIdle()

        assertEquals(2, calls)
        assertEquals(pepe, lastDone().getOrNull())
    }

    @Test
    fun leavingTheFieldOnTextThatIsNotAnAddressSaysWhy() = runTest {
        val reads = mutableListOf<String>()
        val lookup = TypedAddressLookup(this, read = { reads += it; pepe }, onState = { states += it })

        lookup.changed("0x1234")
        advanceUntilIdle()
        lookup.commit()
        assertEquals("Enter a contract address: 0x and 40 hex digits.", lastDone().exceptionOrNull()?.message)

        lookup.changed("0x" + "g".repeat(40))
        lookup.commit()
        assertEquals("Enter a contract address: 0x and 40 hex digits.", lastDone().exceptionOrNull()?.message)

        lookup.changed("0x" + "0".repeat(40))
        lookup.commit()
        assertEquals("That is the zero address, not a token contract.", lastDone().exceptionOrNull()?.message)

        assertEquals(emptyList(), reads)
    }

    @Test
    fun aDismissedTokenIsLookedUpAgainOnDone() = runTest {
        val reads = mutableListOf<String>()
        val lookup = TypedAddressLookup(this, read = { reads += it; pepe }, onState = { states += it })

        lookup.changed(pepeAddress)
        advanceUntilIdle()
        lookup.commit()
        advanceUntilIdle()
        assertEquals(1, reads.size)

        lookup.forget()
        lookup.commit()
        advanceUntilIdle()

        assertEquals(2, reads.size)
        assertEquals(pepe, lastDone().getOrNull())
    }
}
