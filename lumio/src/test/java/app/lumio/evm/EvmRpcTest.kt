package app.lumio.evm

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/** The JSON-RPC client against a local fake endpoint. No test reaches a real network. */
class EvmRpcTest {
    private val server = MockWebServer()
    private val rpc = EvmRpc(allowLocalHttp = true, timeoutMs = 2_000L)
    private val address = "0x" + "ab".repeat(20)

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

    /** The reason of the refusal that [block] raises. Fails the test when [block] does not refuse. */
    private fun refusal(block: () -> Any?): RpcFailure.Reason =
        assertThrows(RpcFailure::class.java) { block() }.reason

    @Test
    fun theChainIdIsReadWithTheChainIdMethod() {
        server.enqueue(answer("0x1"))

        assertEquals(1L, rpc.chainId(url))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"method\":\"eth_chainId\""))
    }

    @Test
    fun theNativeBalanceIsWeiAtTheLatestBlock() {
        server.enqueue(answer("0xde0b6b3a7640000"))

        assertEquals(BigInteger("1000000000000000000"), rpc.balance(url, address))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"eth_getBalance\""))
        assertTrue(body.contains("\"params\":[\"$address\",\"latest\"]"))
    }

    @Test
    fun aZeroBalanceIsZero() {
        server.enqueue(answer("0x0"))

        assertEquals(BigInteger.ZERO, rpc.balance(url, address))
    }

    @Test
    fun theTransactionCountIsTheNonce() {
        server.enqueue(answer("0x2a"))

        assertEquals(42L, rpc.transactionCount(url, address))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"method\":\"eth_getTransactionCount\""))
    }

    @Test
    fun aJsonRpcErrorIsRefused() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}"""))

        assertEquals(RpcFailure.Reason.ERROR, refusal { rpc.chainId(url) })
    }

    @Test
    fun anHttpErrorIsRefused() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(RpcFailure.Reason.ERROR, refusal { rpc.chainId(url) })
    }

    @Test
    fun aRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/elsewhere").toString()))

        assertEquals(RpcFailure.Reason.ERROR, refusal { rpc.chainId(url) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aQuantityThatIsNotHexadecimalIsMalformed() {
        for (bad in listOf("12", "0x", "0xZZ", "")) {
            server.enqueue(answer(bad))
            assertEquals("result $bad", RpcFailure.Reason.MALFORMED, refusal { rpc.balance(url, address) })
        }
    }

    @Test
    fun aNonStringResultIsMalformed() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":5}"""))

        assertEquals(RpcFailure.Reason.MALFORMED, refusal { rpc.chainId(url) })
    }

    @Test
    fun aMissingResultIsMalformed() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1}"""))

        assertEquals(RpcFailure.Reason.MALFORMED, refusal { rpc.chainId(url) })
    }

    @Test
    fun anAnswerThatIsNotJsonIsMalformed() {
        server.enqueue(MockResponse().setBody("<html>"))

        assertEquals(RpcFailure.Reason.MALFORMED, refusal { rpc.chainId(url) })
    }

    @Test
    fun anAnswerLargerThanTheLimitIsMalformed() {
        server.enqueue(answer("0x" + "1".repeat(70_000)))

        assertEquals(RpcFailure.Reason.MALFORMED, refusal { rpc.chainId(url) })
    }

    @Test
    fun anEndpointOfAnotherChainIsRefusedBeforeItsBalancesAreTrusted() {
        server.enqueue(answer("0xaa36a7"))

        assertEquals(RpcFailure.Reason.WRONG_CHAIN, refusal { rpc.requireChain(url, 1L) })
    }

    @Test
    fun anEndpointOfTheExpectedChainPasses() {
        server.enqueue(answer("0xaa36a7"))

        rpc.requireChain(url, 11155111L)
    }

    @Test
    fun plainHttpIsRefusedOutsideADebugBuild() {
        val strict = EvmRpc(allowLocalHttp = false)

        assertEquals(RpcFailure.Reason.REFUSED, refusal { strict.chainId(url) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anEndpointThatDoesNotAnswerIsUnreachable() {
        val gone = MockWebServer()
        gone.start()
        val deadUrl = gone.url("/").toString()
        gone.shutdown()

        assertEquals(RpcFailure.Reason.UNREACHABLE, refusal { rpc.chainId(deadUrl) })
    }

    @Test
    fun aMalformedBalanceAddressIsRefusedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException::class.java) { rpc.balance(url, "0x1234") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theRpcAddressIsTrimmedAndOnlyHttpsIsAcceptedWithoutDebugHttp() {
        assertEquals("https://rpc.example.org/x", RpcUrl.validate("  https://rpc.example.org/x  ", allowLocalHttp = false))
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("ftp://rpc.example.org", allowLocalHttp = false) }
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("http://localhost:8545", allowLocalHttp = false) }
        assertEquals("http://localhost:8545/", RpcUrl.validate("http://localhost:8545", allowLocalHttp = true))
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("http://rpc.example.org", allowLocalHttp = true) }
    }

    @Test
    fun anAddressWithACredentialIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            RpcUrl.validate("https://user:secret@rpc.example.org", allowLocalHttp = false)
        }
        assertFalse(RpcUrl.validate("https://rpc.example.org", allowLocalHttp = false).contains("@"))
    }
}
