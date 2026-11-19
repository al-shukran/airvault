package app.lumio.data.rpc

import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Trait
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        server.shutdown()
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
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/first" -> MockResponse().setResponseCode(429)
                "/second" -> MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val net = NetworkId(Networks.evm(1), testnet = false)
        val urls = listOf(server.url("/first").toString(), server.url("/second").toString())
        val chainlist = ChainlistRpc(table = mapOf("1" to urls), allowLocalHttp = true)

        val balance = runBlocking { chainlist.execute(NativeBalance(net, address), ProviderConfig(), JsonRpc.newHttp(2_000L)) }

        assertEquals(BigInteger.valueOf(42), balance)
        assertEquals("/first", server.takeRequest().path)
        assertEquals("/second", server.takeRequest().path)
    }

    @Test
    fun aFatalAnswerFromTheFirstAddressEndsTheCallWithoutTryingTheSecond() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"no"}}""")
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
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
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
}
