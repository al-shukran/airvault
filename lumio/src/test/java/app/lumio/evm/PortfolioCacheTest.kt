package app.lumio.evm

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import app.lensly.core.wallet.HdWallet
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A refresh stores the reads of each shown address, and an offline refresh keeps them with the time they were read. */
class PortfolioCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 7 + 3).toByte() })
    }
    private val server = MockWebServer()

    @After
    fun stop() {
        server.shutdown()
    }

    /** Answers the two reads from a fake endpoint: one ETH and three transactions on chain 1. */
    private fun answerWithOneEther() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val result = when {
                    body.contains("eth_getBalance") -> "0xde0b6b3a7640000"
                    body.contains("eth_getTransactionCount") -> "0x3"
                    else -> return MockResponse().setResponseCode(400)
                }
                return MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"$result"}""")
            }
        }
        server.start()
    }

    private val mainnet = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val wallet = PairedWallet(
        id = "w1",
        name = "Demo",
        fingerprint = 1L,
        createdAtMs = 0L,
        evm = HdWallet(PairFixtures.seed).accountXpub(),
        bitcoin = emptyList(),
    )

    @Test
    fun aRefreshStoresEachShownAddressAndAnOfflineRefreshKeepsItsReadTime() {
        answerWithOneEther()
        val settings = EvmSettings(addressCount = 2, enabled = setOf(1L), rpc = mapOf(1L to server.url("/").toString()))
        val reads = testReads(settings)

        val online = runBlocking {
            Portfolio.refresh(session, listOf(wallet), settings, listOf(mainnet), reads, nowMs = { 1_000L })
        }

        assertEquals(emptyList<ChainFailure>(), online["w1"])
        val stored = BalanceCache.read(session)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.wei == "1000000000000000000" && it.nonce == 3L && it.atMs == 1_000L })

        server.shutdown()
        val offline = runBlocking {
            Portfolio.refresh(session, listOf(wallet), settings, listOf(mainnet), reads, nowMs = { 2_000L })
        }

        assertEquals(1L, offline["w1"]!!.single().chainId)
        val kept = BalanceCache.read(session)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.atMs == 1_000L && it.wei == "1000000000000000000" })
    }
}
