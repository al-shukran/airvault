package app.lumio.data.rpc

import app.lumio.data.BTC
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Trait
import app.lumio.data.route.ProviderState
import app.lumio.data.route.Router
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each provider's endpoint per chain, its key format and its traits, as the docs cited in its KDoc give them. */
class RpcProvidersTest {
    private val server = MockWebServer()
    private val mainnet = NetworkId(Networks.evm(1), testnet = false)
    private val base = NetworkId(Networks.evm(8453), testnet = false)
    private val key = "fixkey123"

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun oneRpcIsKeylessPerChainAndKeyedInItsPath() {
        assertEquals("https://public.1rpc.io/eth", OneRpc().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://public.1rpc.io/base", OneRpc().endpoint(base, ProviderConfig()))
        assertEquals("https://1rpc.io/$key/eth", OneRpc().endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertNull(OneRpc().endpoint(NetworkId(Networks.evm(999999), testnet = false), ProviderConfig()))
    }

    @Test
    fun aKeyThatWouldChangeThePathIsNotUsed() {
        assertNull(OneRpc().endpoint(mainnet, ProviderConfig(apiKey = "a/b")))
        assertNull(Alchemy().endpoint(mainnet, ProviderConfig(apiKey = "a?b")))
        assertNull(Ankr().endpoint(mainnet, ProviderConfig(apiKey = "a/../b")))
    }

    @Test
    fun publicNodeHasOneHostPerChainAndNoKeyOption() {
        assertEquals("https://ethereum.publicnode.com", PublicNode().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://base.publicnode.com", PublicNode().endpoint(base, ProviderConfig()))
        assertEquals("https://ethereum.publicnode.com", PublicNode().endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertNull(PublicNode().endpoint(NetworkId(Networks.evm(999999), testnet = false), ProviderConfig()))
    }

    @Test
    fun sepoliaIsServedByPublicNodeAndTenderlyButNotByOneRpcsFreePlan() {
        val sepolia = NetworkId(Networks.evm(11155111), testnet = true)
        assertEquals("https://ethereum-sepolia-rpc.publicnode.com", PublicNode().endpoint(sepolia, ProviderConfig()))
        assertEquals("https://sepolia.gateway.tenderly.co", Tenderly().endpoint(sepolia, ProviderConfig()))
        assertNull(OneRpc().endpoint(sepolia, ProviderConfig()))
        assertNull(OneRpc().endpoint(sepolia, ProviderConfig(apiKey = key)))
    }

    @Test
    fun ankrNeedsAKeyAsTheLastPathSegment() {
        assertNull(Ankr().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://rpc.ankr.com/eth/$key", Ankr().endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertFalse(Ankr().supports(mainnet, OpKey.on(mainnet, "nativeBalance"), ProviderConfig()))
        assertTrue(Ankr().supports(mainnet, OpKey.on(mainnet, "nativeBalance"), ProviderConfig(apiKey = key)))
    }

    @Test
    fun dRpcHasOneHostPerChainListedInItsDocs() {
        assertEquals("https://eth.drpc.org", DRpc().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://base.drpc.org", DRpc().endpoint(base, ProviderConfig()))
        assertNull(DRpc().endpoint(NetworkId(Networks.evm(56), testnet = false), ProviderConfig()))
    }

    @Test
    fun alchemyReadsKeylessButBroadcastsOnlyWithAKey() {
        val alchemy = Alchemy()
        assertEquals("https://eth-mainnet.g.alchemy.com/public", alchemy.endpoint(mainnet, ProviderConfig()))
        assertEquals("https://eth-mainnet.g.alchemy.com/v2/$key", alchemy.endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertTrue(alchemy.supports(mainnet, OpKey.on(mainnet, "nativeBalance"), ProviderConfig()))
        assertFalse(alchemy.supports(mainnet, OpKey.on(mainnet, "broadcast"), ProviderConfig()))
        assertTrue(alchemy.supports(mainnet, OpKey.on(mainnet, "broadcast"), ProviderConfig(apiKey = key)))
        assertFalse(alchemy.supports(NetworkId(Networks.evm(137), testnet = false), OpKey.on(NetworkId(Networks.evm(137), testnet = false), "nativeBalance"), ProviderConfig(apiKey = key)))
        assertTrue(Trait.MEV_PROTECTED in alchemy.traits)
    }

    @Test
    fun tenderlyServesEthereumMainnetAsAFallback() {
        assertEquals("https://mainnet.gateway.tenderly.co", Tenderly().endpoint(mainnet, ProviderConfig()))
        assertNull(Tenderly().endpoint(base, ProviderConfig()))
        assertTrue(Trait.FALLBACK_ONLY in Tenderly().traits)
    }

    @Test
    fun theMevProvidersInThePoolAreOneRpcPublicNodeAndKeyedAlchemy() {
        val withMev = RpcPlugins.pool().filter { Trait.MEV_PROTECTED in it.traits }.map { it.id }.toSet()
        assertEquals(setOf("1rpc", "publicnode", "alchemy"), withMev)
        assertFalse(Trait.MEV_PROTECTED in Ankr().traits)
        assertFalse(Trait.MEV_PROTECTED in DRpc().traits)
    }

    @Test
    fun thePoolHoldsTheProvidersOfTheDesignAndChainlist() {
        assertEquals(
            setOf("1rpc", "publicnode", "ankr", "drpc", "alchemy", "tenderly", "chainlist"),
            RpcPlugins.pool().map { it.id }.toSet(),
        )
    }

    @Test
    fun aBroadcastGoesToTheMevProvidersBeforeAnkrOrDrpc() {
        val everyone = stateWith(cooling = emptySet())
        val router = Router(RpcPlugins.pool())
        val keyed = mapOf("ankr" to ProviderConfig(apiKey = key))
        fun offered(net: NetworkId, state: ProviderState): Set<String> =
            router.route(net, OpKey.on(net, "broadcast"), keyed, state, nowMs = 0L, tried = emptySet(), excluded = emptySet()).map { it.id }.toSet()

        assertEquals(setOf("1rpc", "publicnode"), offered(mainnet, everyone))
        assertEquals(setOf("1rpc", "publicnode"), offered(base, everyone))
        assertEquals(setOf("ankr", "drpc"), offered(mainnet, stateWith(cooling = setOf("1rpc", "publicnode"))))
    }

    /** A state in which the listed providers are cooling down and the rest are available. */
    private fun stateWith(cooling: Set<String>): ProviderState = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = providerId !in cooling

        override fun score(providerId: String, nowMs: Long): Double = 0.0
    }

    @Test
    fun noPoolProviderServesANonEvmNetwork() {
        val solana = NetworkId(Networks.SOL_MAINNET, testnet = false)
        RpcPlugins.pool().forEach { p ->
            assertFalse(p.id, p.supports(solana, OpKey.on(solana, "nativeBalance"), ProviderConfig()))
            assertFalse(p.id, p.supports(BTC, OpKey.on(BTC, "nativeBalance"), ProviderConfig()))
        }
    }

    @Test
    fun theOwnerAddressIsServedForItsChainOnlyAndOnlyOverHttpsOutsideADebugBuild() {
        val cfg = ProviderConfig(chainUrls = mapOf("1" to "https://node.example.org/rpc"))
        assertEquals("https://node.example.org/rpc", CustomRpc(allowLocalHttp = false).endpoint(mainnet, cfg))
        assertNull(CustomRpc(allowLocalHttp = false).endpoint(base, cfg))
        val local = ProviderConfig(chainUrls = mapOf("1" to "http://localhost:8545"))
        assertNull(CustomRpc(allowLocalHttp = false).endpoint(mainnet, local))
        assertEquals("http://localhost:8545/", CustomRpc(allowLocalHttp = true).endpoint(mainnet, local))
    }

    @Test
    fun theOwnerAddressAnswersTheBalanceReadOverMockWebServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                return if (body.contains("eth_getBalance")) {
                    MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}""").build()
                } else {
                    MockResponse.Builder().code(400).build()
                }
            }
        }
        server.start()
        val cfg = ProviderConfig(chainUrls = mapOf("1" to server.url("/").toString()))

        val balance = runBlocking {
            CustomRpc(allowLocalHttp = true).execute(NativeBalance(mainnet, "0x" + "1".repeat(40)), cfg, JsonRpc.newHttp(2_000L))
        }

        assertEquals(BigInteger.valueOf(42), balance)
        assertEquals("POST", server.takeRequest().method)
    }
}
