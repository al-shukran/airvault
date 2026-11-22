package app.lumio.data.provider.routescan

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.explorer.ExplorerTx
import app.lumio.data.provider.explorer.explorerAnswer
import app.lumio.data.provider.explorer.normalRow
import app.lumio.data.provider.explorer.tokenRow
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The transaction history of the Routescan plugin, against a local MockWebServer in the shape of its Etherscan-compatible API. No test reaches a
 * network.
 */
class RoutescanTxHistoryTest {
    private val server = MockWebServer()
    private val charges = ArrayList<Unit>()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()) { charges.add(Unit) }
    private val provider = RoutescanProvider()
    private val account = "0x" + "ab".repeat(20)
    private val other = "0x" + "cd".repeat(20)
    private val hash = "0x" + "1a".repeat(32)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun cfg(key: String? = null) = ProviderConfig(baseUrl = server.url("/").toString(), apiKey = key)

    private fun history(cfg: ProviderConfig = cfg(), net: NetworkId = ETH): List<ExplorerTx> = runBlocking {
        provider.execute(AddressTxHistory(net, account, 1, 20), cfg, http)
    }

    @Test
    fun theHistoryUsesTheEtherscanCompatiblePathWithTheSameParameters() {
        server.enqueue(MockResponse.Builder().body(explorerAnswer("0", "No transactions found", "[]")).build())
        server.enqueue(MockResponse.Builder().body(explorerAnswer("0", "No transactions found", "[]")).build())

        history()

        val first = server.takeRequest().url
        assertEquals("/v2/network/mainnet/evm/1/etherscan/api", first.encodedPath)
        assertEquals("account", first.queryParameter("module"))
        assertEquals("txlist", first.queryParameter("action"))
        assertEquals(account, first.queryParameter("address"))
        assertEquals("1", first.queryParameter("page"))
        assertEquals("20", first.queryParameter("offset"))
        assertEquals("desc", first.queryParameter("sort"))
        assertNull(first.queryParameter("apikey"))
        assertEquals("tokentx", server.takeRequest().url.queryParameter("action"))
        assertEquals(1, charges.size)
    }

    @Test
    fun theHistoryIsTheNormalListThenTheTokenList() {
        server.enqueue(MockResponse.Builder().body(explorerAnswer("1", "OK", "[${normalRow(hash, account, other)}]")).build())
        server.enqueue(MockResponse.Builder().body(explorerAnswer("1", "OK", "[${tokenRow(hash, other, account)}]")).build())

        val rows = history()

        assertEquals(2, rows.size)
        assertIs<ExplorerTx.Normal>(rows[0])
        assertIs<ExplorerTx.Token>(rows[1])
    }

    @Test
    fun theKeyIsAHeaderOfBothListsAndNeverAQueryParameter() {
        server.enqueue(MockResponse.Builder().body(explorerAnswer("0", "No transactions found", "[]")).build())
        server.enqueue(MockResponse.Builder().body(explorerAnswer("0", "No transactions found", "[]")).build())

        history(cfg("rs_test"))

        repeat(2) {
            val request = server.takeRequest()
            assertEquals("rs_test", request.headers["apikey"])
            assertNull(request.url.queryParameter("apikey"))
        }
    }

    @Test
    fun aChainThatTheKeylessApiDoesNotServeIsUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { history(net = NetworkId(Networks.evm(8453), testnet = false)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRateLimitOnAListIsRateLimitedWithItsRetryAfter() {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "5").build())

        assertEquals(5_000L, assertFailsWith<ProviderError.RateLimited> { history() }.retryAfterMs)
    }
}
