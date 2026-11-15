package app.lumio.data.rpc

import app.lumio.data.BTC
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each provider's endpoint per chain, its key format and its traits, as the docs cited in its KDoc give them. */
class RpcProvidersTest {
    private val server = MockWebServer()
    private val mainnet = NetworkId(Family.EVM, "1", testnet = false)
    private val base = NetworkId(Family.EVM, "8453", testnet = false)
    private val key = "fixkey123"

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun oneRpcIsKeylessPerChainAndKeyedInItsPath() {
        assertEquals("https://public.1rpc.io/eth", OneRpc().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://public.1rpc.io/base", OneRpc().endpoint(base, ProviderConfig()))
        assertEquals("https://1rpc.io/$key/eth", OneRpc().endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertNull(OneRpc().endpoint(NetworkId(Family.EVM, "999999", testnet = false), ProviderConfig()))
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
        assertNull(PublicNode().endpoint(NetworkId(Family.EVM, "11155111", testnet = true), ProviderConfig()))
    }

    @Test
    fun ankrNeedsAKeyAsTheLastPathSegment() {
        assertNull(Ankr().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://rpc.ankr.com/eth/$key", Ankr().endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertFalse(Ankr().supports(mainnet, Op.NATIVE_BALANCE, ProviderConfig()))
        assertTrue(Ankr().supports(mainnet, Op.NATIVE_BALANCE, ProviderConfig(apiKey = key)))
    }

    @Test
    fun dRpcHasOneHostPerChainListedInItsDocs() {
        assertEquals("https://eth.drpc.org", DRpc().endpoint(mainnet, ProviderConfig()))
        assertEquals("https://base.drpc.org", DRpc().endpoint(base, ProviderConfig()))
        assertNull(DRpc().endpoint(NetworkId(Family.EVM, "56", testnet = false), ProviderConfig()))
    }

    @Test
    fun alchemyReadsKeylessButBroadcastsOnlyWithAKey() {
        val alchemy = Alchemy()
        assertEquals("https://eth-mainnet.g.alchemy.com/public", alchemy.endpoint(mainnet, ProviderConfig()))
        assertEquals("https://eth-mainnet.g.alchemy.com/v2/$key", alchemy.endpoint(mainnet, ProviderConfig(apiKey = key)))
        assertTrue(alchemy.supports(mainnet, Op.NATIVE_BALANCE, ProviderConfig()))
        assertFalse(alchemy.supports(mainnet, Op.BROADCAST, ProviderConfig()))
        assertTrue(alchemy.supports(mainnet, Op.BROADCAST, ProviderConfig(apiKey = key)))
        assertFalse(alchemy.supports(NetworkId(Family.EVM, "137", testnet = false), Op.NATIVE_BALANCE, ProviderConfig(apiKey = key)))
        assertTrue(Trait.MEV_PROTECTED in alchemy.traits)
    }

    @Test
    fun tenderlyServesEthereumMainnetAsAFallback() {
        assertEquals("https://mainnet.gateway.tenderly.co", Tenderly().endpoint(mainnet, ProviderConfig()))
        assertNull(Tenderly().endpoint(base, ProviderConfig()))
        assertTrue(Trait.FALLBACK_ONLY in Tenderly().traits)
    }

    @Test
    fun onlyAlchemyClaimsMevProtectionInThePool() {
        val withMev = RpcPlugins.pool().filter { Trait.MEV_PROTECTED in it.traits }.map { it.id }
        assertEquals(listOf("alchemy"), withMev)
    }

    @Test
    fun thePoolHoldsTheSixProvidersOfTheDesign() {
        assertEquals(setOf("1rpc", "publicnode", "ankr", "drpc", "alchemy", "tenderly"), RpcPlugins.pool().map { it.id }.toSet())
    }

    @Test
    fun noPoolProviderServesANonEvmNetwork() {
        val solana = NetworkId(Family.SOL, "mainnet", testnet = false)
        RpcPlugins.pool().forEach { p ->
            assertFalse(p.id, p.supports(solana, Op.NATIVE_BALANCE, ProviderConfig()))
            assertFalse(p.id, p.supports(BTC, Op.NATIVE_BALANCE, ProviderConfig()))
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
                val body = request.body.readUtf8()
                return if (body.contains("eth_getBalance")) {
                    MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}""")
                } else {
                    MockResponse().setResponseCode(400)
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
