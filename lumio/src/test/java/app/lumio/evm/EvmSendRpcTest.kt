package app.lumio.evm

import app.lensly.core.evm.EvmFixtures
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.web3j.crypto.Hash

/** The send's network reads and its broadcast, against a local fake endpoint. No test reaches a real network. */
class EvmSendRpcTest {
    private val server = MockWebServer()
    private val rpc = EvmRpc(allowLocalHttp = true, timeoutMs = 2_000L)
    private val chain = EvmChain(
        chainId = 11155111L,
        name = "Sepolia",
        symbol = "ETH",
        decimals = 18,
        testnet = true,
        explorerUrl = "https://sepolia.etherscan.io",
        defaultRpc = null,
    )
    private val from = AccountAddresses.addressAt(HdWallet(Mnemonic.toSeed(EvmFixtures.artMnemonic)).accountXpub(), 0)
    private val to = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val oneCent = BigInteger.TEN.pow(16)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private val url: String get() = server.url("/").toString()

    private fun answer(result: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"$result"}""")

    private fun feeHistory(base: String) = MockResponse().setBody(
        """{"jsonrpc":"2.0","id":1,"result":{"oldestBlock":"0x1","baseFeePerGas":["$base","$base"],"gasUsedRatio":[0.5],"reward":[["0x1"]]}}""",
    )

    private fun refusal() = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}""")

    private fun hex(value: BigInteger) = "0x" + value.toString(16)

    /** The answers of a clean send: chain 11155111, a balance of [balance], nonce 7, a 1.5 gwei tip, a 1 gwei base fee, and [gas] gas. */
    private fun enqueueReads(balance: BigInteger = BigInteger.TEN.pow(18), gas: MockResponse = answer("0x5208")) {
        server.enqueue(answer("0xaa36a7"))
        server.enqueue(answer(hex(balance)))
        server.enqueue(answer("0x7"))
        server.enqueue(answer("0x59682f00"))
        server.enqueue(feeHistory("0x3b9aca00"))
        server.enqueue(gas)
    }

    @Test
    fun aSendIsPreparedFromThePendingNonceTheFeesAndTheEstimate() {
        enqueueReads()

        val send = EvmSend.prepare(rpc, url, chain, from, 0, to, oneCent)

        assertEquals(7L, send.nonce)
        assertEquals(BigInteger.valueOf(1_500_000_000L), send.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(3_500_000_000L), send.maxFeePerGas)
        assertEquals(BigInteger.valueOf(21_000L), send.gasLimit)
        val bodies = List(6) { server.takeRequest().body.readUtf8() }
        assertTrue(bodies[0].contains("\"method\":\"eth_chainId\""))
        assertTrue(bodies[1].contains("\"method\":\"eth_getBalance\"") && bodies[1].contains("\"params\":[\"$from\",\"latest\"]"))
        assertTrue(bodies[2].contains("\"method\":\"eth_getTransactionCount\"") && bodies[2].contains("\"params\":[\"$from\",\"pending\"]"))
        assertTrue(bodies[3].contains("\"method\":\"eth_maxPriorityFeePerGas\""))
        assertTrue(bodies[4].contains("\"method\":\"eth_feeHistory\"") && bodies[4].contains("\"params\":[\"0x1\",\"latest\",[]]"))
        assertTrue(bodies[5].contains("\"method\":\"eth_estimateGas\""))
        assertTrue(bodies[5].contains("\"from\":\"$from\"") && bodies[5].contains("\"to\":\"$to\""))
        assertTrue(bodies[5].contains("\"value\":\"${hex(oneCent)}\""))
    }

    @Test
    fun anEstimateThatTheEndpointRefusesFallsBackToThePlainTransferGas() {
        enqueueReads(gas = refusal())

        assertEquals(BigInteger.valueOf(21_000L), EvmSend.prepare(rpc, url, chain, from, 0, to, oneCent).gasLimit)
    }

    @Test
    fun aBalanceThatCannotCoverTheSendIsRefusedWithItsCost() {
        enqueueReads(balance = BigInteger.TEN.pow(15))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            EvmSend.prepare(rpc, url, chain, from, 0, to, oneCent)
        }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().startsWith("The balance is 0.001 ETH, but this send can cost up to "))
    }

    @Test
    fun anEndpointOfAnotherChainIsRefusedBeforeAnythingElseIsRead() {
        server.enqueue(answer("0x1"))

        val failure = assertThrows(RpcFailure::class.java) { EvmSend.prepare(rpc, url, chain, from, 0, to, oneCent) }

        assertEquals(RpcFailure.Reason.WRONG_CHAIN, failure.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFeeHistoryWithoutABaseFeeIsMalformed() {
        server.enqueue(answer("0xaa36a7"))
        server.enqueue(answer("0xde0b6b3a7640000"))
        server.enqueue(answer("0x7"))
        server.enqueue(answer("0x59682f00"))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"baseFeePerGas":[]}}"""))

        assertEquals(RpcFailure.Reason.MALFORMED, assertThrows(RpcFailure::class.java) {
            EvmSend.prepare(rpc, url, chain, from, 0, to, oneCent)
        }.reason)
    }

    @Test
    fun aBroadcastReturnsTheHashWhenItIsTheHashOfTheSignedTransaction() {
        val raw = byteArrayOf(0x02, 0x01, 0x02, 0x03)
        val expected = "0x" + Hash.sha3(raw).toHex()
        server.enqueue(answer(expected))

        assertEquals(expected, EvmSend.broadcast(rpc, url, raw))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"eth_sendRawTransaction\""))
        assertTrue(body.contains("\"params\":[\"0x02010203\"]"))
    }

    @Test
    fun aBroadcastThatReportsAnotherHashIsRefused() {
        server.enqueue(answer("0x" + "11".repeat(32)))

        val failure = assertThrows(RpcFailure::class.java) { EvmSend.broadcast(rpc, url, byteArrayOf(0x02)) }

        assertEquals(RpcFailure.Reason.MALFORMED, failure.reason)
    }

    @Test
    fun aBroadcastThatTheEndpointRefusesIsAnRpcError() {
        server.enqueue(refusal())

        assertEquals(RpcFailure.Reason.ERROR, assertThrows(RpcFailure::class.java) { EvmSend.broadcast(rpc, url, byteArrayOf(0x02)) }.reason)
    }

    @Test
    fun aBroadcastAnswerThatIsNotAHashIsMalformed() {
        server.enqueue(answer("0x1234"))

        assertEquals(RpcFailure.Reason.MALFORMED, assertThrows(RpcFailure::class.java) { rpc.sendRawTransaction(url, byteArrayOf(0x02)) }.reason)
    }
}
