package app.lumio.evm

import app.lensly.core.wallet.toHex
import app.lumio.send.Landing
import app.lumio.tokens.Erc20
import app.lumio.tokens.Token
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys

/** A send reads its balance, nonce, fees and gas through the scheduler, and a broadcast is checked against the signed transaction's hash. */
class EvmSendRpcTest {
    private val server = MockWebServer()
    private val chain = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val from = "0x" + "11".repeat(20)
    private val to = "0x" + "22".repeat(20)
    private val HASH = "0x" + "ab".repeat(32)
    private val oneCent = BigInteger.TEN.pow(16)
    private val answers = HashMap<String, String>()
    private var failing = HashSet<String>()
    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val bodies = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                bodies += body
                val method = Regex("\"method\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                if (method in failing) return MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}""").build()
                val result = answers[method] ?: return MockResponse.Builder().code(400).build()
                return MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":$result}""").build()
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
        server.close()
    }

    private fun reads(): EvmReads = testReads(EvmSettings(rpc = mapOf(1L to rpcInUse(server.url("/").toString()))))

    /** The answer to a balanceOf read: one word, in the token's base units. */
    private fun balanceAnswer(units: BigInteger): String = "\"0x" + units.toString(16).padStart(64, '0') + "\""

    /** The first parameter object of the recorded [method] call, as its string fields. */
    private fun paramsOf(method: String): Map<String, String> {
        val body = bodies.first { it.contains("\"method\":\"$method\"") }
        val first = Json.parseToJsonElement(body).jsonObject["params"]!!.jsonArray[0].jsonObject
        return first.mapValues { it.value.jsonPrimitive.content }
    }

    private fun methodsCalled(): List<String> = bodies.map { Regex("\"method\":\"([^\"]+)\"").find(it)?.groupValues?.get(1).orEmpty() }

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

    @Test
    fun aTokenSendReadsTheTokenBalanceAndEstimatesTheTransferToTheContract() {
        answers["eth_call"] = balanceAnswer(BigInteger.valueOf(100_000_000L))

        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, BigInteger.valueOf(12_500_000L), usdc) }

        assertEquals(BigInteger.valueOf(100_000_000L), send.tokenBalance)
        assertEquals(Keys.toChecksumAddress(usdc.contract), send.tx.to)
        val estimate = paramsOf("eth_estimateGas")
        assertEquals(from, estimate["from"])
        assertEquals(Keys.toChecksumAddress(usdc.contract), estimate["to"])
        assertEquals("0x0", estimate["value"])
        assertEquals(Erc20.transfer(to, BigInteger.valueOf(12_500_000L)), estimate["data"])
        assertEquals(usdc.contract, paramsOf("eth_call")["to"])
        assertEquals(Erc20.balanceOf(from), paramsOf("eth_call")["data"])
    }

    @Test
    fun aTokenEstimateThatTheEndpointRefusesFallsBackToTheTokenTransferGas() {
        answers["eth_call"] = balanceAnswer(BigInteger.valueOf(100_000_000L))
        failing.add("eth_estimateGas")

        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, BigInteger.valueOf(12_500_000L), usdc) }

        assertEquals(BigInteger.valueOf(65_000L), send.gasLimit)
    }

    @Test
    fun aTokenBalanceBelowTheAmountIsRefusedBeforeTheGasIsRead() {
        answers["eth_call"] = balanceAnswer(BigInteger.valueOf(10_000_000L))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, BigInteger.valueOf(12_500_000L), usdc) }
        }

        assertEquals("The token balance is 10 USDC, but this send is 12.5 USDC.", failure.message)
        assertEquals(false, methodsCalled().contains("eth_estimateGas"))
    }

    @Test
    fun theNativeBalanceMustCoverTheGasOfATokenSend() {
        answers["eth_call"] = balanceAnswer(BigInteger.valueOf(100_000_000L))
        answers["eth_getBalance"] = "\"0x1\""

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, BigInteger.valueOf(12_500_000L), usdc) }
        }

        assertEquals(true, failure.message?.startsWith("The balance is "))
        assertEquals(true, failure.message?.contains("but the gas for this send can cost up to"))
    }

    @Test
    fun aTokenBalanceThatDoesNotDecodeIsARefusedRead() {
        answers["eth_call"] = "\"0x\""

        val failure = assertThrows(RpcFailure::class.java) {
            runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, BigInteger.valueOf(12_500_000L), usdc) }
        }

        assertEquals(RpcFailure.Reason.MALFORMED, failure.reason)
        assertEquals("The token balance could not be read. Try again.", failure.message)
    }

    @Test
    fun aSentTransactionIsPendingWhileItsNonceIsFree() {
        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }
        answers["eth_getTransactionReceipt"] = "null"

        assertEquals(Landing.PENDING, runBlocking { EvmSendLanding(reads(), send).landing(HASH) })
    }

    @Test
    fun aDroppedTransactionIsRefusedOnlyOnTheSecondPollThatSeesIt() {
        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }
        val watch = EvmSendLanding(reads(), send)
        answers["eth_getTransactionReceipt"] = "null"
        answers["eth_getTransactionCount"] = "\"0x8\""

        assertEquals(Landing.PENDING, runBlocking { watch.landing(HASH) })
        assertEquals(Landing.REFUSED, runBlocking { watch.landing(HASH) })
    }

    @Test
    fun aLaggingReceiptDoesNotRefuseASentTransaction() {
        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }
        val watch = EvmSendLanding(reads(), send)
        answers["eth_getTransactionReceipt"] = "null"
        answers["eth_getTransactionCount"] = "\"0x8\""

        assertEquals(Landing.PENDING, runBlocking { watch.landing(HASH) })

        answers["eth_getTransactionReceipt"] = "{\"status\":\"0x1\"}"
        assertEquals(Landing.LANDED, runBlocking { watch.landing(HASH) })
    }

    @Test
    fun aReceiptDecidesTheSentTransaction() {
        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }
        val watch = EvmSendLanding(reads(), send)
        answers["eth_getTransactionCount"] = "\"0x8\""

        answers["eth_getTransactionReceipt"] = "{\"status\":\"0x1\"}"
        assertEquals(Landing.LANDED, runBlocking { watch.landing(HASH) })

        answers["eth_getTransactionReceipt"] = "{\"status\":\"0x0\"}"
        assertEquals(Landing.REFUSED, runBlocking { watch.landing(HASH) })
    }

    @Test
    fun theConfirmedNonceIsReadBeforeTheReceipt() {
        val send = runBlocking { EvmSend.prepare(reads(), chain, from, 0, to, oneCent) }
        answers["eth_getTransactionReceipt"] = "null"
        bodies.clear()

        runBlocking { EvmSendLanding(reads(), send).landing(HASH) }

        assertEquals(listOf("eth_getTransactionCount", "eth_getTransactionReceipt"), methodsCalled())
        assertEquals(true, bodies.first().contains("\"latest\""))
    }
}
