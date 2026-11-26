package app.lumio.data.rpc

import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Trait
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chains that the public pool must serve, and the chainlist addresses that serve them. */
class ChainlistRpcTest {
    private val server = MockWebServer()
    private val address = "0x" + "1".repeat(40)

    /** The 27 chains that lost their default RPC when the pool replaced the default table. */
    private val wanted = listOf(
        "10143", "1088", "122", "1285", "13371", "143", "167000", "1868", "196", "2020", "232", "252", "2741", "30", "33139",
        "3338", "369", "388", "42170", "480", "48900", "57073", "60808", "61", "747474", "7777777", "9745",
    )

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun everyWantedChainResolvesAtLeastOneRpcForBalancesAndBroadcasts() {
        for (chain in wanted) {
            val net = NetworkId(Networks.evm(chain.toLong()), testnet = chain == "10143")
            assertTrue("chain $chain has no balance RPC", RpcPlugins.pool().any { it.supports(net, OpKey.on(net, "nativeBalance"), ProviderConfig()) })
            assertTrue("chain $chain has no broadcast RPC", RpcPlugins.pool().any { it.supports(net, OpKey.on(net, "broadcast"), ProviderConfig()) })
        }
    }

    @Test
    fun theTableHoldsExactlyTheWantedChainsWithOneOrTwoDistinctHttpsAddresses() {
        assertEquals(wanted.toSet(), ChainlistRpc.ENDPOINTS.keys)
        for ((chain, urls) in ChainlistRpc.ENDPOINTS) {
            assertTrue("chain $chain holds one or two addresses", urls.size in 1..2)
            assertEquals("chain $chain repeats an address", urls.size, urls.toSet().size)
            urls.forEach { RpcUrl.validate(it, allowLocalHttp = false) }
        }
    }

    @Test
    fun monadTestnetIsServedKeylessWithItsOwnAddress() {
        val net = NetworkId(Networks.evm(10143), testnet = true)
        assertEquals(listOf("https://testnet-rpc.monad.xyz"), ChainlistRpc().endpoints(net, ProviderConfig()))
        assertTrue(ChainlistRpc().supports(net, OpKey.on(net, "nativeBalance"), ProviderConfig()))
        assertTrue(ChainlistRpc().supports(net, OpKey.on(net, "broadcast"), ProviderConfig()))
    }

    @Test
    fun chainlistIsKeylessFallbackOnlyAndMakesNoMevClaim() {
        val traits = ChainlistRpc().traits
        assertTrue(Trait.FALLBACK_ONLY in traits)
        assertTrue(Trait.KEYLESS in traits)
        assertFalse(Trait.MEV_PROTECTED in traits)
    }

    @Test
    fun aRateLimitOnTheFirstAddressMovesTheCallToTheSecond() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/first" -> MockResponse.Builder().code(429).build()
                "/second" -> MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}""").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val urls = listOf(server.url("/first").toString(), server.url("/second").toString())
        val chainlist = ChainlistRpc(table = mapOf("1" to urls), allowLocalHttp = true)

        val balance = runBlocking { chainlist.execute(NativeBalance(net, address), ProviderConfig(), JsonRpc.newHttp(2_000L)) }

        assertEquals(BigInteger.valueOf(42), balance)
        assertEquals("/first", server.takeRequest().target)
        assertEquals("/second", server.takeRequest().target)
    }

    @Test
    fun aFatalAnswerFromTheFirstAddressEndsTheCallWithoutTryingTheSecond() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"no"}}""").build()
        }
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val urls = listOf(server.url("/first").toString(), server.url("/second").toString())
        val chainlist = ChainlistRpc(table = mapOf("1" to urls), allowLocalHttp = true)

        val failure = runCatching {
            runBlocking { chainlist.execute(NativeBalance(net, address), ProviderConfig(), JsonRpc.newHttp(2_000L)) }
        }.exceptionOrNull()

        assertTrue(failure is ProviderError.Fatal)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun whenEveryAddressFailsTransientlyTheLastFailureIsThrown() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(500).build()
        }
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val urls = listOf(server.url("/first").toString(), server.url("/second").toString())
        val chainlist = ChainlistRpc(table = mapOf("1" to urls), allowLocalHttp = true)

        val failure = runCatching {
            runBlocking { chainlist.execute(NativeBalance(net, address), ProviderConfig(), JsonRpc.newHttp(2_000L)) }
        }.exceptionOrNull()

        assertTrue(failure is ProviderError.Transient)
        assertEquals(2, server.requestCount)
    }

    private fun noRetryHttp() = Http(OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build())

    @Test
    fun aBroadcastThatTheFirstAddressDroppedAfterTheRequestIsNotSentToTheSecond() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/first" -> MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
                else -> MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"already known"}}""").build()
            }
        }
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val urls = listOf(server.url("/first").toString(), server.url("/second").toString())
        val chainlist = ChainlistRpc(table = mapOf("1" to urls), allowLocalHttp = true)

        val failure = runCatching {
            runBlocking { chainlist.execute(Broadcast(net, byteArrayOf(2)), ProviderConfig(), noRetryHttp()) }
        }.exceptionOrNull()

        assertTrue(failure is ProviderError.Transient)
        assertFalse((failure as ProviderError.Transient).neverSent)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBroadcastThatTheFirstAddressRefusedBeforeAnyByteMovesToTheSecond() {
        val closed = MockWebServer().apply { start(); }
        val closedUrl = closed.url("/first").toString()
        closed.close()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"0x${"ab".repeat(32)}"}""").build()
        }
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val chainlist = ChainlistRpc(table = mapOf("1" to listOf(closedUrl, server.url("/second").toString())), allowLocalHttp = true)

        val hash = runBlocking { chainlist.execute(Broadcast(net, byteArrayOf(2)), ProviderConfig(), noRetryHttp()) }

        assertEquals("0x" + "ab".repeat(32), hash)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aBroadcastThatTheFirstAddressDroppedStaysMaybeSentWhenTheSecondAddressIsClosed() {
        val closed = MockWebServer().apply { start() }
        val closedUrl = closed.url("/second").toString()
        closed.close()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val chainlist = ChainlistRpc(table = mapOf("1" to listOf(server.url("/first").toString(), closedUrl)), allowLocalHttp = true)

        val failure = runCatching { runBlocking { chainlist.execute(Broadcast(net, byteArrayOf(2)), ProviderConfig(), noRetryHttp()) } }.exceptionOrNull()

        assertTrue(failure is ProviderError.Transient)
        assertFalse((failure as ProviderError.Transient).neverSent)
        assertEquals(1, server.requestCount)
    }
}
