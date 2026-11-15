package app.lumio.data.provider.blockscout

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.Http
import app.lumio.data.provider.Op
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.explorer.ExplorerTx
import app.lumio.data.provider.explorer.explorerAnswer
import app.lumio.data.provider.explorer.normalRow
import app.lumio.data.provider.explorer.tokenRow
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The transaction history of the Blockscout plugin, against a local MockWebServer in the shapes that the account docs give. No test reaches a network.
 */
class BlockscoutTxHistoryTest {
    private val server = MockWebServer()
    private val charges = ArrayList<Unit>()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()) { charges.add(Unit) }
    private val account = "0x" + "ab".repeat(20)
    private val other = "0x" + "cd".repeat(20)
    private val hash = "0x" + "1a".repeat(32)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun provider() = BlockscoutProvider(bases = mapOf("1" to server.url("/").toString()))

    private fun history(cfg: ProviderConfig = ProviderConfig(), page: Int = 1, offset: Int = 20): List<ExplorerTx> = runBlocking {
        provider().execute(AddressTxHistory(ETH, account, page, offset), cfg, http)
    }

    @Test
    fun theHistoryIsTheNormalListThenTheTokenListOfTheInstance() {
        server.enqueue(MockResponse().setBody(explorerAnswer("1", "OK", "[${normalRow(hash, account, other)}]")))
        server.enqueue(MockResponse().setBody(explorerAnswer("1", "OK", "[${tokenRow(hash, other, account)}]")))

        val rows = history(page = 2, offset = 25)

        assertEquals(2, rows.size)
        assertIs<ExplorerTx.Normal>(rows[0])
        assertIs<ExplorerTx.Token>(rows[1])
        val normal = server.takeRequest().requestUrl!!
        assertEquals("/api", normal.encodedPath)
        assertEquals("account", normal.queryParameter("module"))
        assertEquals("txlist", normal.queryParameter("action"))
        assertEquals(account, normal.queryParameter("address"))
        assertEquals("2", normal.queryParameter("page"))
        assertEquals("25", normal.queryParameter("offset"))
        assertEquals("desc", normal.queryParameter("sort"))
        assertEquals("tokentx", server.takeRequest().requestUrl!!.queryParameter("action"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theSecondListIsChargedToTheCallsRateBudgetBeforeItIsSent() {
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))

        history()

        assertEquals(1, charges.size)
    }

    @Test
    fun theKeyGoesInTheApikeyParameterOfBothLists() {
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))

        history(ProviderConfig(apiKey = "proapi_test"))

        assertEquals("proapi_test", server.takeRequest().requestUrl!!.queryParameter("apikey"))
        assertEquals("proapi_test", server.takeRequest().requestUrl!!.queryParameter("apikey"))
    }

    @Test
    fun anAccountWithNoMatchesHasAnEmptyHistory() {
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))

        assertEquals(emptyList(), history())
    }

    @Test
    fun aRateLimitOnTheTokenListRefusesTheWholeCallWithItsRetryAfter() {
        server.enqueue(MockResponse().setBody(explorerAnswer("1", "OK", "[${normalRow(hash, account, other)}]")))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

        val error = assertFailsWith<ProviderError.RateLimited> { history() }

        assertEquals(30_000L, error.retryAfterMs)
    }

    @Test
    fun aRowThatDoesNotNameTheAccountFailsTheCall() {
        val stranger = "0x" + "ef".repeat(20)
        server.enqueue(MockResponse().setBody(explorerAnswer("1", "OK", "[${normalRow(hash, other, stranger)}]")))
        server.enqueue(MockResponse().setBody(explorerAnswer("0", "No transactions found", "[]")))

        assertFailsWith<ProviderError.Fatal> { history() }
    }

    @Test
    fun theHistoryIsServedOnlyByTheHostedMainnetInstance() {
        val c = ProviderConfig()
        assertTrue(provider().supports(ETH, Op.TX_HISTORY, c))
        assertFalse(provider().supports(NetworkId(Family.EVM, "8453", testnet = false), Op.TX_HISTORY, c))
        assertFalse(provider().supports(NetworkId(Family.EVM, "1", testnet = true), Op.TX_HISTORY, c))
        assertFailsWith<ProviderError.Unsupported> {
            runBlocking { provider().execute(AddressTxHistory(NetworkId(Family.EVM, "8453", testnet = false), account, 1, 20), c, http) }
        }
        assertEquals(0, server.requestCount)
    }
}
