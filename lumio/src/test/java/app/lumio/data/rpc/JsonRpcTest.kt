package app.lumio.data.rpc

import app.lumio.data.provider.ProviderError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The shared JSON-RPC client, against a local MockWebServer: its answers, its failures, and the address rules every call applies. */
class JsonRpcTest {
    private val server = MockWebServer()
    private val client = JsonRpc.newHttp(timeoutMs = 2_000L).client

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun call(vararg responses: MockResponse): JsonElement {
        responses.forEach { server.enqueue(it) }
        return JsonRpc.call(client, server.url("/").toString(), "eth_chainId", JsonArray(emptyList()), allowLocalHttp = true)
    }

    private fun refusal(vararg responses: MockResponse): ProviderError =
        assertThrows(ProviderError::class.java) { call(*responses) }

    private fun answer(result: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$result}""")

    @Test
    fun aResultIsReturnedFromTheAnswer() {
        assertEquals("\"0x1\"", call(answer("\"0x1\"")).toString())
    }

    @Test
    fun theCallIsASingleJsonRpcPostWithItsMethodAndParams() {
        call(answer("\"0x1\""))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"method\":\"eth_chainId\""))
        assertTrue(body.contains("\"params\":[]"))
    }

    @Test
    fun aTooManyRequestsAnswerIsRateLimitedWithItsRetryAfter() {
        val failure = refusal(MockResponse().setResponseCode(429).addHeader("Retry-After", "7"))

        assertEquals(7_000L, (failure as ProviderError.RateLimited).retryAfterMs)
    }

    @Test
    fun aJsonRpcLimitErrorIsRateLimited() {
        val failure = refusal(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32005,"message":"limit"}}"""))

        assertTrue(failure is ProviderError.RateLimited)
    }

    @Test
    fun aJsonRpcErrorThatNamesARateLimitIsRateLimited() {
        val failure = refusal(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":30,"message":"You reached rate limit"}}"""))

        assertTrue(failure is ProviderError.RateLimited)
    }

    @Test
    fun aServerErrorIsTransient() {
        assertTrue(refusal(MockResponse().setResponseCode(503)) is ProviderError.Transient)
    }

    @Test
    fun aRefusedKeyIsFatal() {
        assertTrue(refusal(MockResponse().setResponseCode(401)) is ProviderError.Fatal)
    }

    @Test
    fun aRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/moved").toString()))
        server.enqueue(answer("\"0x1\""))

        val failure = assertThrows(ProviderError::class.java) {
            JsonRpc.call(client, server.url("/").toString(), "eth_chainId", JsonArray(emptyList()), allowLocalHttp = true)
        }

        assertTrue(failure is ProviderError.Fatal)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aJsonRpcErrorIsFatal() {
        assertTrue(refusal(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}""")) is ProviderError.Fatal)
    }

    @Test
    fun anAnswerThatIsNotJsonIsFatal() {
        assertTrue(refusal(MockResponse().setBody("<html>")) is ProviderError.Fatal)
    }

    @Test
    fun aMissingOrNullResultIsFatal() {
        assertTrue(refusal(MockResponse().setBody("""{"jsonrpc":"2.0","id":1}""")) is ProviderError.Fatal)
        assertTrue(refusal(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":null}""")) is ProviderError.Fatal)
    }

    @Test
    fun anAnswerLargerThanTheLimitIsFatal() {
        val large = "{\"result\":\"" + "a".repeat(65_600) + "\"}"

        assertTrue(refusal(MockResponse().setBody(large)) is ProviderError.Fatal)
    }

    @Test
    fun anEndpointThatDoesNotAnswerIsTransient() {
        server.shutdown()

        assertTrue(refusal() is ProviderError.Transient)
    }

    @Test
    fun plainHttpIsRefusedOutsideADebugBuild() {
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("http://localhost:8545", allowLocalHttp = false) }
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("http://rpc.example.org", allowLocalHttp = true) }
        assertEquals("http://localhost:8545/", RpcUrl.validate("http://localhost:8545", allowLocalHttp = true))
    }

    @Test
    fun anAddressIsTrimmedAndKeepsItsPathOverHttps() {
        assertEquals("https://rpc.example.org/x", RpcUrl.validate("  https://rpc.example.org/x  ", allowLocalHttp = false))
    }

    @Test
    fun anAddressWithACredentialOrANonWebSchemeIsRefusedWithoutEchoingTheSecret() {
        val credential = assertThrows(IllegalArgumentException::class.java) {
            RpcUrl.validate("https://user:secret@rpc.example.org", allowLocalHttp = false)
        }
        assertFalse(credential.message.orEmpty().contains("secret"))
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("ftp://rpc.example.org", allowLocalHttp = false) }
        assertThrows(IllegalArgumentException::class.java) { RpcUrl.validate("not a url", allowLocalHttp = false) }
    }

    @Test
    fun anAddressLongerThanTheLimitIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            RpcUrl.validate("https://" + "a".repeat(600) + ".example.org", allowLocalHttp = false)
        }
    }

    @Test
    fun aRetryAfterIsCappedAtTheOneCapEveryProviderShares() {
        val failure = refusal(MockResponse().setResponseCode(429).addHeader("Retry-After", "7200"))

        assertEquals(3_600_000L, (failure as ProviderError.RateLimited).retryAfterMs)
    }

    @Test
    fun anErrorThatSaysTheChainIsNotServedOnThePlanIsUnsupportedNotFatal() {
        val planRefusal = """{"jsonrpc":"2.0","id":1,"error":{"code":35,"message":"chain is not available on free plan, please upgrade to paid plan"}}"""
        val noSuchMethod = """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"the method does not exist"}}"""

        assertTrue(refusal(MockResponse().setBody(planRefusal)) is ProviderError.Unsupported)
        assertTrue(refusal(MockResponse().setBody(noSuchMethod)) is ProviderError.Unsupported)
        assertTrue(refusal(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nope"}}""")) is ProviderError.Fatal)
    }
}
