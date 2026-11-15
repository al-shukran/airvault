package app.lumio.tokens

import app.lumio.evm.EvmSettings
import app.lumio.evm.RpcFailure
import app.lumio.evm.testReads
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The balance reads of tokens: one Multicall3 call for the batch, single reads when the batch is not there, and each failure's effect. */
class TokenBalancesTest {
    private val multicall = Multicall3.ADDRESS

    private fun read(reads: ScriptedReads, contracts: List<String> = listOf(USDC, DAI)): Map<String, BigInteger?> =
        runBlocking { TokenBalances.read(reads, ETHEREUM, OWNER, contracts) }

    @Test
    fun aBatchReadsEveryTokenInOneCall() {
        val reads = ScriptedReads { to, _ ->
            check(to == multicall)
            Reply.aggregate(listOf(true to Reply.uint(1_000), false to ""))
        }

        assertEquals(mapOf(USDC to BigInteger.valueOf(1_000), DAI to null), read(reads))
        assertEquals(1, reads.calls.size)
    }

    @Test
    fun aChainWithoutMulticallReadsEachTokenOnItsOwn() {
        val reads = ScriptedReads { to, _ ->
            when (to) {
                multicall -> "0x"
                USDC -> "0x" + Reply.uint(7)
                else -> "0x"
            }
        }

        assertEquals(mapOf(USDC to BigInteger.valueOf(7), DAI to null), read(reads))
        assertEquals(3, reads.calls.size)
    }

    @Test
    fun aBatchThatRevertsFallsBackToSingleReads() {
        val reads = ScriptedReads { to, _ ->
            when (to) {
                multicall -> throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint returned a JSON-RPC error.")
                USDC -> "0x" + Reply.uint(5)
                else -> throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint returned a JSON-RPC error.")
            }
        }

        assertEquals(mapOf(USDC to BigInteger.valueOf(5), DAI to null), read(reads))
    }

    @Test
    fun anUnreachableEndpointFailsTheWholeRead() {
        val reads = ScriptedReads { _, _ -> throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.") }

        assertEquals(RpcFailure.Reason.UNREACHABLE, assertFailsWith<RpcFailure> { read(reads) }.reason)
    }

    @Test
    fun aBatchWithTheWrongNumberOfAnswersIsMalformedNotZero() {
        val reads = ScriptedReads { _, _ -> Reply.aggregate(listOf(true to Reply.uint(1))) }

        assertEquals(RpcFailure.Reason.MALFORMED, assertFailsWith<RpcFailure> { read(reads) }.reason)
    }

    @Test
    fun aTokenListedTwiceIsReadOnceAndNothingIsReadForNoTokens() {
        val reads = ScriptedReads { _, _ -> Reply.aggregate(listOf(true to Reply.uint(3))) }

        assertEquals(mapOf(USDC to BigInteger.valueOf(3)), read(reads, listOf(USDC, USDC)))
        assertEquals(emptyMap<String, BigInteger?>(), read(ScriptedReads { _, _ -> "0x" }, emptyList()))
        assertTrue(reads.calls.first().second.startsWith(Multicall3.AGGREGATE3))
    }
}

/**
 * The same reads over JSON-RPC, through the EVM reads and the owner's RPC address: the call goes out as eth_call at the latest block, to the
 * contract, with the call data, and the answer comes back as hex.
 */
class TokenBalancesOverRpcTest {
    private val server = MockWebServer()
    private val calls = ArrayList<JsonObject>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).let { it as JsonObject }
                val params = body["params"] as JsonArray
                val transaction = params[0] as JsonObject
                calls += transaction
                assertEquals("latest", (params[1] as JsonPrimitive).content)
                assertEquals("eth_call", (body["method"] as JsonPrimitive).content)
                val to = (transaction["to"] as JsonPrimitive).content
                val result = if (to == Multicall3.ADDRESS) Reply.aggregate(listOf(true to Reply.uint(1_234_567), true to Reply.uint(9))) else "0x"
                return MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x${result.removePrefix("0x")}"}""")
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun theBatchGoesOutAsEthCallAndTheBalancesComeBack() {
        val settings = EvmSettings(rpc = mapOf(1L to server.url("/").toString()))
        val balances = runBlocking { TokenBalances.read(testReads(settings), ETHEREUM, OWNER, listOf(USDC, DAI)) }

        assertEquals(mapOf(USDC to BigInteger.valueOf(1_234_567), DAI to BigInteger.valueOf(9)), balances)
        assertEquals(1, calls.size)
        assertEquals(Multicall3.ADDRESS, (calls[0]["to"] as JsonPrimitive).content)
        assertTrue((calls[0]["data"] as JsonPrimitive).content.startsWith(Multicall3.AGGREGATE3))
    }
}
