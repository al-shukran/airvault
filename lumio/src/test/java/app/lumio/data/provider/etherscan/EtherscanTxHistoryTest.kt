package app.lumio.data.provider.etherscan

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The transaction history of the Etherscan V2 plugin, against a local MockWebServer. No test reaches a network. */
class EtherscanTxHistoryTest {
    private val server = MockWebServer()
    private val charges = ArrayList<Unit>()
    private val http = Http(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()) { charges.add(Unit) }
    private val provider = EtherscanProvider()
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

    private fun cfg(key: String?) = ProviderConfig(baseUrl = server.url("/").toString(), apiKey = key)

    private fun history(cfg: ProviderConfig): List<ExplorerTx> = runBlocking { provider.execute(AddressTxHistory(ETH, account, 1, 20), cfg, http) }

    @Test
    fun withoutAKeyTheHistoryIsUnsupportedAndNeverCalled() {
        assertFailsWith<ProviderError.Unsupported> { history(cfg(null)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theHistoryNamesTheChainAndSendsTheKeyAsTheApikeyParameter() {
        server.enqueue(MockResponse.Builder().body(explorerAnswer("0", "No transactions found", "[]")).build())
        server.enqueue(MockResponse.Builder().body(explorerAnswer("0", "No transactions found", "[]")).build())

        history(cfg("ek_test"))

        val first = server.takeRequest().url
        assertEquals("/v2/api", first.encodedPath)
        assertEquals("1", first.queryParameter("chainid"))
        assertEquals("account", first.queryParameter("module"))
        assertEquals("txlist", first.queryParameter("action"))
        assertEquals(account, first.queryParameter("address"))
        assertEquals("desc", first.queryParameter("sort"))
        assertEquals("ek_test", first.queryParameter("apikey"))
        val second = server.takeRequest().url
        assertEquals("tokentx", second.queryParameter("action"))
        assertEquals("ek_test", second.queryParameter("apikey"))
        assertEquals(1, charges.size)
    }

    @Test
    fun theHistoryIsTheNormalListThenTheTokenList() {
        server.enqueue(MockResponse.Builder().body(explorerAnswer("1", "OK", "[${normalRow(hash, account, other)}]")).build())
        server.enqueue(MockResponse.Builder().body(explorerAnswer("1", "OK", "[${tokenRow(hash, other, account)}]")).build())

        val rows = history(cfg("ek_test"))

        assertEquals(2, rows.size)
        assertIs<ExplorerTx.Normal>(rows[0])
        assertIs<ExplorerTx.Token>(rows[1])
    }
}
