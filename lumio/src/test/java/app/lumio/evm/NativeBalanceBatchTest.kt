package app.lumio.evm

import app.lumio.tokens.Abi
import app.lumio.tokens.Multicall3
import app.lumio.tokens.Reply
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The native balances of many addresses: one Multicall3 call per batch, single reads as the fallback, and each failure's effect. */
class NativeBalanceBatchTest {
    private fun addressOf(i: Int) = "0x" + "%040x".format(i + 1)

    private fun addresses(n: Int) = (0 until n).map(::addressOf)

    /** The balance each test address holds: 1000 wei plus its index. */
    private fun weiOf(address: String) = BigInteger.valueOf(1_000L + address.removePrefix("0x").toLong(16) - 1)

    /** The addresses that a Multicall3 request asks for, in order, read back from its getEthBalance call data. */
    private fun addressesOf(request: String): List<String> {
        val bytes = Abi.bytes("0x" + request.removePrefix(Multicall3.AGGREGATE3))
        val base = Abi.index(bytes, 0)
        val count = Abi.index(bytes, base)
        val items = base + Abi.WORD
        return (0 until count).map { i ->
            val tuple = items + Abi.index(bytes, items + i * Abi.WORD)
            val callData = tuple + Abi.index(bytes, tuple + 2 * Abi.WORD)
            val length = Abi.index(bytes, callData)
            val data = bytes.copyOfRange(callData + Abi.WORD, callData + Abi.WORD + length)
            // The call data is the selector and one address word, so the address is its last 20 bytes.
            "0x" + Abi.hex(data).removePrefix("0x").takeLast(40)
        }
    }

    /** The answer to a Multicall3 request: each address's balance, except that each address in [fail] reports its call as failed. */
    private fun answerOf(request: String, fail: Set<String> = emptySet()): String =
        Reply.aggregate(addressesOf(request).map { address -> (address !in fail) to Reply.uint(weiOf(address).toLong()) })

    @Test
    fun oneEthCallReadsTheBalanceOfEveryAddress() = runBlocking {
        val requests = ArrayList<String>()

        val balances = readBalances(
            addresses(8),
            call = { to, data ->
                check(to == Multicall3.ADDRESS)
                requests += data
                answerOf(data)
            },
            single = { error("A batch that answers needs no single read.") },
        )

        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith(Multicall3.AGGREGATE3))
        assertEquals(addresses(8), addressesOf(requests.single()))
        assertEquals(addresses(8).map(::weiOf), addresses(8).map { balances.getValue(it).getOrThrow() })
    }

    @Test
    fun aLargeSetIsReadInOneCallPerBatch() = runBlocking {
        val requests = ArrayList<String>()

        val balances = readBalances(
            addresses(120),
            call = { _, data ->
                requests += data
                answerOf(data)
            },
            single = { error("A batch that answers needs no single read.") },
        )

        assertEquals(listOf(50, 50, 20), requests.map { addressesOf(it).size })
        assertEquals(weiOf(addressOf(119)), balances.getValue(addressOf(119)).getOrThrow())
        assertEquals(120, balances.size)
    }

    @Test
    fun aChainWithoutMulticallReadsEachAddressOnItsOwn() = runBlocking {
        val singles = ArrayList<String>()

        val balances = readBalances(
            addresses(3),
            call = { _, _ -> "0x" },
            single = { address ->
                singles += address
                weiOf(address)
            },
        )

        assertEquals(addresses(3).toSet(), singles.toSet())
        assertEquals(3, singles.size)
        assertEquals(addresses(3).map(::weiOf), addresses(3).map { balances.getValue(it).getOrThrow() })
    }

    @Test
    fun aMulticallThatErrorsFallsBackToSingleReads() = runBlocking {
        val singles = ArrayList<String>()

        val balances = readBalances(
            addresses(2),
            call = { _, _ -> throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint returned a JSON-RPC error.") },
            single = { address ->
                singles += address
                weiOf(address)
            },
        )

        assertEquals(addresses(2).toSet(), singles.toSet())
        assertEquals(addresses(2).map(::weiOf), addresses(2).map { balances.getValue(it).getOrThrow() })
    }

    @Test
    fun anAnswerThatDoesNotDecodeFallsBackToSingleReads() = runBlocking {
        val singles = ArrayList<String>()

        val balances = readBalances(
            addresses(2),
            call = { _, _ -> "0x1234" },
            single = { address ->
                singles += address
                weiOf(address)
            },
        )

        assertEquals(addresses(2).toSet(), singles.toSet())
        assertEquals(weiOf(addressOf(1)), balances.getValue(addressOf(1)).getOrThrow())
    }

    @Test
    fun aFailedCallInsideTheBatchFailsOnlyItsAddress() = runBlocking {
        val singles = ArrayList<String>()

        val balances = readBalances(
            addresses(3),
            call = { _, data -> answerOf(data, fail = setOf(addressOf(1))) },
            single = { address ->
                singles += address
                weiOf(address)
            },
        )

        assertTrue(singles.isEmpty())
        assertTrue(balances.getValue(addressOf(1)).isFailure)
        assertEquals(weiOf(addressOf(0)), balances.getValue(addressOf(0)).getOrThrow())
        assertEquals(weiOf(addressOf(2)), balances.getValue(addressOf(2)).getOrThrow())
    }

    @Test
    fun aFailedSingleReadFailsOnlyItsAddress() = runBlocking {
        val balances = readBalances(
            addresses(3),
            call = { _, _ -> "0x" },
            single = { address ->
                if (address == addressOf(1)) throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.")
                weiOf(address)
            },
        )

        assertEquals(RpcFailure.Reason.UNREACHABLE, (balances.getValue(addressOf(1)).exceptionOrNull() as RpcFailure).reason)
        assertEquals(weiOf(addressOf(0)), balances.getValue(addressOf(0)).getOrThrow())
        assertEquals(weiOf(addressOf(2)), balances.getValue(addressOf(2)).getOrThrow())
    }

    @Test
    fun anUnreachableEndpointFailsTheWholeRead() {
        val singles = ArrayList<String>()

        val failure = assertFailsWith<RpcFailure> {
            runBlocking {
                readBalances(
                    addresses(3),
                    call = { _, _ -> throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.") },
                    single = { address ->
                        singles += address
                        weiOf(address)
                    },
                )
            }
        }

        assertEquals(RpcFailure.Reason.UNREACHABLE, failure.reason)
        assertTrue(singles.isEmpty())
    }
}
