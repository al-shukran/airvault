package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.pair.PairFixtures
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The gap-limit discovery and the account scan, against the fake Esplora on a local MockWebServer. */
class BtcScanTest {
    private val fake = FakeEsplora()
    private val server = MockWebServer()
    private lateinit var base: String

    private val account = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val ref = BtcAccountRef("wallet-1", account)

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun start() {
        server.dispatcher = fake
        server.start()
        base = server.url("/api").toString()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun scan(nowMs: Long = 1_000L): BtcSnapshot = runBlocking { scanAccount(ref, EsploraClient(base), base, nowMs) }

    @Test
    fun aChainStopsAfterTwentyUnusedAddressesInARow() {
        fake.stats[account.receiveAddress(0)] = addressJson(chain = side(fundedCount = 1, fundedSum = 10_000, txCount = 1))

        val snapshot = scan()

        // Receive 0 is used, so the walk asks receive 0 to 20 (twenty unused in a row). The change chain has nothing, so it asks 0 to 19.
        val expected = (0..20).map { account.receiveAddress(it) } + (0..19).map { account.changeAddress(it) }
        assertEquals(expected, fake.statsRequests())
        assertEquals(10_000L, snapshot.confirmedSats)
        assertEquals(1, snapshot.nextReceiveIndex)
        assertEquals(account.receiveAddress(1), snapshot.nextReceiveAddress)
    }

    @Test
    fun theWalkGoesOnAcrossAGapShorterThanTheLimit() {
        // Receive 0 and 20 are used, with 19 unused addresses between them. That gap is short of the limit, so the walk continues.
        fake.stats[account.receiveAddress(0)] = addressJson(chain = side(fundedCount = 1, fundedSum = 1_000, txCount = 1))
        fake.stats[account.receiveAddress(20)] = addressJson(chain = side(fundedCount = 1, fundedSum = 2_000, txCount = 1))
        // Change 3 is used on the mempool only, with 700 sats still unconfirmed.
        fake.stats[account.changeAddress(3)] = addressJson(mempool = side(fundedCount = 1, fundedSum = 700, txCount = 1))

        val snapshot = scan()

        // Receive: 0 to 40, since 21 to 40 are the twenty unused after 20. Change: 0 to 23, since 4 to 23 are the twenty unused after 3.
        val expected = (0..40).map { account.receiveAddress(it) } + (0..23).map { account.changeAddress(it) }
        assertEquals(expected, fake.statsRequests())
        assertEquals(3_000L, snapshot.confirmedSats)
        assertEquals(700L, snapshot.unconfirmedSats)
        assertEquals(1, snapshot.nextReceiveIndex)
    }

    @Test
    fun theNextReceiveAddressIsTheFirstUnusedIndex() {
        for (index in 0..2) {
            fake.stats[account.receiveAddress(index)] = addressJson(chain = side(fundedCount = 1, fundedSum = 100, txCount = 1))
        }

        val snapshot = scan()

        assertEquals(3, snapshot.nextReceiveIndex)
        assertEquals(account.receiveAddress(3), snapshot.nextReceiveAddress)
    }

    @Test
    fun transactionsAreDedupedSortedAndNetOfTheAccountsOwnAddresses() {
        val receive0 = account.receiveAddress(0)
        val change3 = account.changeAddress(3)
        // Receive 0 got 1,000 sats at block 849,990. The spend of them is unconfirmed: it pays 600 back to change 3 and 300 to a stranger.
        fake.stats[receive0] = addressJson(
            chain = side(fundedCount = 1, fundedSum = 1_000, txCount = 1),
            mempool = side(spentCount = 1, spentSum = 1_000, txCount = 1),
        )
        fake.stats[change3] = addressJson(mempool = side(fundedCount = 1, fundedSum = 600, txCount = 1))
        val received = """{"txid":"tx-received","vin":[],"vout":[{"scriptpubkey_address":"$receive0","value":1000}],""" +
            """"status":{"confirmed":true,"block_height":849990,"block_time":1700000000}}"""
        val spend = """{"txid":"tx-spend","vin":[{"txid":"tx-received","vout":0,"prevout":{"scriptpubkey_address":"$receive0",""" +
            """"value":1000}}],"vout":[{"scriptpubkey_address":"$change3","value":600},{"scriptpubkey_address":"tb1qstranger","value":300}],""" +
            """"status":{"confirmed":false}}"""
        // Both addresses report the spend, and the scan must count it once.
        fake.txs[receive0] = "[$spend,$received]"
        fake.txs[change3] = "[$spend]"
        fake.utxos[change3] = """[{"txid":"tx-spend","vout":0,"status":{"confirmed":false},"value":600}]"""

        val snapshot = scan()

        assertEquals(1_000L, snapshot.confirmedSats)
        assertEquals(-400L, snapshot.unconfirmedSats, "the spend takes 1,000 from receive 0 and gives 600 back to change 3")
        assertEquals(listOf("tx-spend", "tx-received"), snapshot.transactions.map { it.txid })
        assertEquals(-400L, snapshot.transactions[0].netSats)
        assertEquals(1_000L, snapshot.transactions[1].netSats)
        assertEquals(11L, snapshot.confirmations(snapshot.transactions[1]), "block 849,990 under a tip of 850,000")
        assertEquals(0L, snapshot.confirmations(snapshot.transactions[0]))
        assertEquals(listOf(BtcUtxo("tx-spend", 0, 600, null)), snapshot.utxos)
        assertEquals(2, fake.paths.count { it.endsWith("/txs") }, "one transactions request per used address")
    }

    @Test
    fun aFullySpentAddressIsNotAskedForItsUnspentOutputs() {
        val receive0 = account.receiveAddress(0)
        fake.stats[receive0] = addressJson(chain = side(fundedCount = 1, fundedSum = 500, spentCount = 1, spentSum = 500, txCount = 2))

        scan()

        assertTrue(fake.paths.none { it == "/api/address/$receive0/utxo" })
    }

    @Test
    fun aNetworkThatFailsIsTriedOnceAndItsAccountsKeepTheirCache() {
        fake.status = 503
        val second = BtcAccountRef("wallet-1", PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT))

        val report = runBlocking { scanAll(listOf(ref, second), mapOf(BtcNetwork.TESTNET4 to base), store()) { 1_000L } }

        assertTrue(report.fresh.isEmpty())
        assertEquals("The server answered HTTP 503.", report.failures[BtcNetwork.TESTNET4])
        assertEquals(1, fake.paths.size, "only the first account's tip was asked; the second account of that network was skipped")
    }

    @Test
    fun aSuccessfulScanIsSavedAndReturned() {
        val store = store()

        val report = runBlocking { scanAll(listOf(ref), mapOf(BtcNetwork.TESTNET4 to base), store) { 2_000L } }

        assertTrue(report.failures.isEmpty())
        assertEquals(report.fresh[ref.key], store.snapshot(ref.key))
        assertEquals(2_000L, store.snapshot(ref.key)?.scannedAtMs)
    }

    @Test
    fun theWalkStopsAfterTwentyUnusedIndicesWithoutAnyServer() {
        val asked = mutableListOf<String>()
        val chain = runBlocking {
            scanChain(
                addressAt = { "a$it" },
                statsOf = { address ->
                    asked += address
                    if (address == "a3" || address == "a7") usedStats else unusedStats
                },
            )
        }

        assertEquals(listOf(3, 7), chain.used.map { it.index })
        // Used 3 and 7, then twenty unused, 8 to 27.
        assertEquals((0..27).map { "a$it" }, asked)
    }

    @Test
    fun aMempoolSpendOfAConfirmedOutputIsANegativeUnconfirmedBalance() {
        val receive0 = account.receiveAddress(0)
        fake.stats[receive0] = addressJson(
            chain = side(fundedCount = 1, fundedSum = 800, txCount = 1),
            mempool = side(spentCount = 1, spentSum = 800, txCount = 1),
        )

        val snapshot = scan()

        assertEquals(800L, snapshot.confirmedSats)
        assertEquals(-800L, snapshot.unconfirmedSats)
    }

    private fun store() = BtcStore(VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() }))

    private val unusedStats = EsploraAddressStats(EsploraSide(0, 0, 0, 0, 0), EsploraSide(0, 0, 0, 0, 0))
    private val usedStats = EsploraAddressStats(EsploraSide(1, 5, 0, 0, 1), EsploraSide(0, 0, 0, 0, 0))
}
