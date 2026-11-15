package app.lumio.evm

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.web3j.crypto.Hash
import org.junit.Assert.assertThrows

/** A send reads its balance, nonce, fees and gas through the scheduler, and a broadcast is checked against the signed transaction's hash. */
class EvmSendRpcTest {
    private val server = MockWebServer()
    private val chain = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val from = "0x" + "11".repeat(20)
    private val to = "0x" + "22".repeat(20)
    private val oneCent = BigInteger.TEN.pow(16)
    private val answers = HashMap<String, String>()
    private var failing = HashSet<String>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val method = Regex("\"method\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                if (method in failing) return MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}""")
                val result = answers[method] ?: return MockResponse().setResponseCode(400)
                return MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$result}""")
            }
        }
        server.start()
        answers["eth_getBalance"] = "\"0x" + BigInteger.TEN.pow(18).toString(16) + "\""
        answers["eth_getTransactionCount"] = "\"0x7\""
        answers["eth_feeHistory"] = "{\"baseFeePerGas\":[\"0x3b9aca00\",\"0x3b9aca00\"]}"
        answers["eth_maxPriorityFeePerGas"] = "\"0x3b9aca00\""
        answers["eth_estimateGas"] = "\"0x5208\""
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun reads(): EvmReads = testReads(EvmSettings(rpc = mapOf(1L to server.url("/").toString())))

    @Test
    fun aSendIsPreparedFromThePendingNonceTheFeesAndTheEstimate() {
        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }

        assertEquals(7L, send.nonce)
        assertEquals(BigInteger.valueOf(1_000_000_000L), send.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(3_000_000_000L), send.maxFeePerGas)
        assertEquals(BigInteger.valueOf(21_000L), send.gasLimit)
        assertEquals(BigInteger.TEN.pow(18), send.balanceWei)
    }

    @Test
    fun anEstimateThatTheEndpointRefusesFallsBackToThePlainTransferGas() {
        failing.add("eth_estimateGas")

        assertEquals(BigInteger.valueOf(21_000L), runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }.gasLimit)
    }

    @Test
    fun aBalanceThatCannotCoverTheSendIsRefusedWithItsCost() {
        answers["eth_getBalance"] = "\"0x1\""

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }
        }
        assertEquals(true, failure.message?.startsWith("The balance is"))
    }

    @Test
    fun aFeeHistoryWithoutABaseFeeIsRefused() {
        answers["eth_feeHistory"] = "{}"

        val failure = assertThrows(RpcFailure::class.java) { runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) } }
        assertEquals(RpcFailure.Reason.ERROR, failure.reason)
    }

    @Test
    fun aBroadcastReturnsTheHashWhenItIsTheHashOfTheSignedTransaction() {
        val raw = byteArrayOf(0x02, 0x01, 0x02)
        val expected = "0x" + Hash.sha3(raw).toHex()
        answers["eth_sendRawTransaction"] = "\"$expected\""

        assertEquals(expected, runBlocking { EvmSend.broadcast(reads(), chain, raw) })
    }

    @Test
    fun aBroadcastThatReportsAnotherHashIsRefused() {
        answers["eth_sendRawTransaction"] = "\"0x" + "ab".repeat(32) + "\""

        val failure = assertThrows(RpcFailure::class.java) { runBlocking { EvmSend.broadcast(reads(), chain, byteArrayOf(0x02)) } }
        assertEquals(RpcFailure.Reason.MALFORMED, failure.reason)
    }

    @Test
    fun aBroadcastThatTheEndpointRefusesIsAnRpcError() {
        failing.add("eth_sendRawTransaction")

        val failure = assertThrows(RpcFailure::class.java) { runBlocking { EvmSend.broadcast(reads(), chain, byteArrayOf(0x02)) } }
        assertEquals(RpcFailure.Reason.ERROR, failure.reason)
    }

    @Test
    fun aBroadcastAnswerThatIsNotAHashIsRefused() {
        answers["eth_sendRawTransaction"] = "\"0x1234\""

        val failure = assertThrows(RpcFailure::class.java) { runBlocking { EvmSend.broadcast(reads(), chain, byteArrayOf(0x02)) } }
        assertEquals(RpcFailure.Reason.ERROR, failure.reason)
    }
}
