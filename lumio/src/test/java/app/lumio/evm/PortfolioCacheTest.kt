package app.lumio.evm

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import app.lumio.tokens.Reply
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
        server.close()
    }

    /** Answers the reads from a fake endpoint: one ETH on each address in one Multicall3 call. */
    private fun answerWithOneEther() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                val result = when {
                    body.contains("eth_call") -> Reply.aggregate(listOf(true to Reply.uint(1_000_000_000_000_000_000L), true to Reply.uint(1_000_000_000_000_000_000L)))
                    else -> return MockResponse.Builder().code(400).build()
                }
                return MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"$result"}""").build()
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
        evmAccounts = PairFixtures.evmAccounts(2),
        bitcoin = emptyList(),
    )

    @Test
    fun aRefreshStoresEachShownAddressAndAnOfflineRefreshKeepsItsReadTime() {
        answerWithOneEther()
        val settings = EvmSettings(addressCount = 2, enabled = setOf(1L), rpc = mapOf(1L to rpcInUse(server.url("/").toString())))
        val reads = testReads(settings)

        val online = runBlocking {
            Portfolio.refresh(session, listOf(wallet), settings, listOf(mainnet), reads, nowMs = { 1_000L })
        }

        assertEquals(emptyList<ChainFailure>(), online["w1"])
        val stored = BalanceCache.read(session)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.wei == "1000000000000000000" && it.atMs == 1_000L })

        server.close()
        val offline = runBlocking {
            Portfolio.refresh(session, listOf(wallet), settings, listOf(mainnet), reads, nowMs = { 2_000L })
        }

        assertEquals(1L, offline["w1"]!!.single().chainId)
        val kept = BalanceCache.read(session)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.atMs == 1_000L && it.wei == "1000000000000000000" })
    }
}
