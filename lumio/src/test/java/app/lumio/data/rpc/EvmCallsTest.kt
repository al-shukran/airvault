package app.lumio.data.rpc

import app.lumio.data.ETH
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.EstimateGas
import app.lumio.data.provider.EvmReceipt
import app.lumio.data.provider.GasFees
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Nonce
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TipHeight
import app.lumio.data.provider.TxReceipt
import java.math.BigInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The EVM request mapping: each request's JSON-RPC method, its parameters, and how its answer is read. */
class EvmCallsTest {
    private val server = MockWebServer()
    private val client = JsonRpc.newHttp(timeoutMs = 2_000L).client
    private val bodies = ArrayList<String>()
    private val address = "0x" + "ab".repeat(20)
    private val hash = "0x" + "cd".repeat(32)

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                bodies.add(body)
                val method = Regex("\"method\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                val result = answers[method] ?: return MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}""").build()
                return MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":$result}""").build()
            }
        }
        server.start()
    }

    private val answers = HashMap<String, String>()

    @After
    fun stop() {
        server.close()
    }

    private fun <T> run(req: app.lumio.data.provider.Request<T>): T =
        EvmCalls.execute(client, server.url("/").toString(), allowLocalHttp = true, req = req)

    @Test
    fun aBalanceIsTheWeiAtTheLatestBlock() {
        answers["eth_getBalance"] = "\"0xde0b6b3a7640000\""

        assertEquals(BigInteger.TEN.pow(18), run(NativeBalance(ETH, address)))
        assertTrue(bodies.single().contains("\"params\":[\"$address\",\"latest\"]"))
    }

    @Test
    fun aReceiptIsNullUntilTheNetworkIncludesTheTransaction() {
        answers["eth_getTransactionReceipt"] = "null"

        assertNull(run(TxReceipt(ETH, hash)))
        assertTrue(bodies.single().contains("\"params\":[\"$hash\"]"))
    }

    @Test
    fun aReceiptReadsWhetherTheTransferSucceeded() {
        answers["eth_getTransactionReceipt"] = "{\"status\":\"0x1\",\"blockNumber\":\"0x10\"}"
        assertEquals(EvmReceipt(success = true), run(TxReceipt(ETH, hash)))

        answers["eth_getTransactionReceipt"] = "{\"status\":\"0x0\",\"blockNumber\":\"0x10\"}"
        assertEquals(EvmReceipt(success = false), run(TxReceipt(ETH, hash)))
    }

    @Test
    fun aReceiptWithoutAStatusIsMalformed() {
        answers["eth_getTransactionReceipt"] = "{}"

        assertThrows(ProviderError.Fatal::class.java) { run(TxReceipt(ETH, hash)) }
    }

    @Test
    fun aPendingNonceAsksForThePendingCount() {
        answers["eth_getTransactionCount"] = "\"0x5\""

        assertEquals(5L, run(Nonce(ETH, address, pending = true)))
        assertTrue(bodies.single().contains("\"params\":[\"$address\",\"pending\"]"))
    }

    @Test
    fun aLatestNonceAsksForTheLatestCount() {
        answers["eth_getTransactionCount"] = "\"0x2\""

        assertEquals(2L, run(Nonce(ETH, address, pending = false)))
        assertTrue(bodies.single().contains("\"latest\""))
    }

    @Test
    fun anAddressThatIsNotAnEvmAddressIsRefusedBeforeAnyCall() {
        assertThrows(IllegalArgumentException::class.java) { run(NativeBalance(ETH, "0x123")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theFeesAreTheNewestBaseFeeAndTheSuggestedPriorityFee() {
        answers["eth_feeHistory"] = "{\"baseFeePerGas\":[\"0x1\",\"0x2\"]}"
        answers["eth_maxPriorityFeePerGas"] = "\"0x3b9aca00\""

        val quote = run(GasFees(ETH))

        assertEquals(BigInteger.TWO, quote.baseFee)
        assertEquals(BigInteger.valueOf(1_000_000_000L), quote.priorityFee)
    }

    @Test
    fun aBaseFeeOrTipThatDoesNotFitA63BitWordIsFatal() {
        answers["eth_feeHistory"] = "{\"baseFeePerGas\":[\"0x8000000000000000\"]}"
        answers["eth_maxPriorityFeePerGas"] = "\"0x1\""
        assertTrue(assertThrows(ProviderError::class.java) { run(GasFees(ETH)) } is ProviderError.Fatal)

        answers["eth_feeHistory"] = "{\"baseFeePerGas\":[\"0x1\"]}"
        answers["eth_maxPriorityFeePerGas"] = "\"0x8000000000000000\""
        assertTrue(assertThrows(ProviderError::class.java) { run(GasFees(ETH)) } is ProviderError.Fatal)
    }

    @Test
    fun aFeeHistoryWithoutABaseFeeIsFatal() {
        answers["eth_feeHistory"] = "{}"

        assertTrue(assertThrows(ProviderError::class.java) { run(GasFees(ETH)) } is ProviderError.Fatal)
    }

    @Test
    fun theGasEstimateSendsTheTransferAsAHexValue() {
        answers["eth_estimateGas"] = "\"0x5208\""

        assertEquals(BigInteger.valueOf(21_000L), run(EstimateGas(ETH, address, address, BigInteger.TEN.pow(16))))
        assertTrue(bodies.single().contains("\"value\":\"0x2386f26fc10000\""))
    }

    @Test
    fun aBroadcastReturnsTheHashInLowercase() {
        val hash = "0x" + "AB".repeat(32)
        answers["eth_sendRawTransaction"] = "\"$hash\""

        assertEquals(hash.lowercase(), run(Broadcast(ETH, byteArrayOf(0x02, 0x0a))))
        assertTrue(bodies.single().contains("\"params\":[\"0x020a\"]"))
    }

    @Test
    fun aBroadcastAnswerThatIsNotAHashIsUnreadable() {
        answers["eth_sendRawTransaction"] = "\"0x1234\""

        assertTrue(assertThrows(ProviderError::class.java) { run(Broadcast(ETH, byteArrayOf(0x02))) } is ProviderError.Unreadable)
    }

    @Test
    fun aQuantityThatIsNotHexadecimalIsFatal() {
        assertEquals(BigInteger.ZERO, parseQuantity("0x0"))
        assertEquals(BigInteger.valueOf(255), parseQuantity("0xFF"))
        assertTrue(assertThrows(ProviderError::class.java) { parseQuantity("10") } is ProviderError.Fatal)
    }

    @Test
    fun aRequestThatIsNotAnEvmCallIsUnsupported() {
        assertTrue(assertThrows(ProviderError::class.java) { run(TipHeight(ETH)) } is ProviderError.Unsupported)
    }

    @Test
    fun theChainIdIsReadWithTheChainIdMethod() {
        answers["eth_chainId"] = "\"0xaa36a7\""

        assertEquals(11155111L, EvmCalls.chainId(client, server.url("/").toString(), allowLocalHttp = true))
    }
}
