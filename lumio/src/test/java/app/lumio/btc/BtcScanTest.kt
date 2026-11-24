package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.data.FakeClock
import app.lumio.data.FakeKvDao
import app.lumio.data.MonotonicClock
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.cache.DEFAULT_SHORT_TTL_MS
import app.lumio.data.electrum.Answer
import app.lumio.data.electrum.ElectrumError
import app.lumio.data.electrum.ElectrumPool
import app.lumio.data.electrum.ElectrumServer
import app.lumio.data.electrum.FakeElectrum
import app.lumio.data.electrum.TestIdentities
import app.lumio.data.electrum.Trust
import app.lumio.data.electrum.scripthash
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.scan.QueuedJob
import app.lumio.data.scan.ScanJob
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.pair.PairFixtures
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The gap-limit scan through the scheduler, against two local Esplora servers that stand in for mempool.space and blockstream.info. The scan's
 * numbers are checked here; a server that answers 429 is served by the other one, with no wait.
 */
class BtcScanTest {
    private val account = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val ref = BtcAccountRef("wallet-1", account)
    private val first = FakeEsplora()
    private val second = FakeEsplora()
    private val serverA = MockWebServer()
    private val serverB = MockWebServer()

    @Before
    fun start() {
        serverA.dispatcher = first
        serverB.dispatcher = second
        serverA.start()
        serverB.start()
    }

    @After
    fun stop() {
        serverA.close()
        serverB.close()
    }

    /** A scheduler over the two servers, as the mempool and blockstream plugins, both serving testnet4. */
    private fun CoroutineScope.scheduler(): Scheduler = Scheduler(
        providers = listOf(
            MempoolProvider(bases = mapOf("testnet4" to serverA.url("/api").toString())),
            BlockstreamProvider(bases = mapOf("testnet4" to serverB.url("/api").toString())),
        ),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = MonotonicClock,
        random = Random(11),
        scope = this,
    )

    /** Both servers answer the same history: the first receive address holds [sats] confirmed, in one unspent output. */
    private fun fund(sats: Long) {
        val address = account.receiveAddress(0)
        val utxo = """[{"txid":"${"a".repeat(64)}","vout":0,"value":$sats,"status":{"confirmed":true,"block_height":850000}}]"""
        for (server in listOf(first, second)) {
            server.stats[address] = addressJson(chain = side(fundedSum = sats, fundedCount = 1, txCount = 1))
            server.utxos[address] = utxo
        }
    }

    @Test
    fun aScanReadsTheBalanceAndTheUnspentOutputsAndStopsAtTheGapLimit() = runBlocking {
        fund(sats = 120_000L)
        val scheduler = this.scheduler()
        val steps = mutableListOf<Int>()

        val snapshot = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 5_000L, step = { steps += it })

        assertEquals(120_000L, snapshot.confirmedSats)
        assertEquals(1, snapshot.utxos.size)
        assertEquals(1, snapshot.nextReceiveIndex, "index 0 is used, so the next receive address is index 1")
        // The receive chain needs index 0 and its 20-address gap: 21 reads. The change chain has no history: 20 reads. The gap is read in windows
        // of 20, so the reads of a window may run past the stop, and each read that is sent is asked for: 41 requests at least, 60 at most.
        val asked = first.statsRequests().size + second.statsRequests().size
        assertTrue(asked in 41..60, "the scan asked for $asked addresses")
        assertEquals((1..steps.size).toList(), steps.sorted(), "each address asked for is reported once, as the scan goes")
        assertTrue(first.statsRequests().isNotEmpty() && second.statsRequests().isNotEmpty(), "the two servers share the requests")
    }

    @Test
    fun aRateLimitedServerIsServedByTheOtherWithoutAWait() = runBlocking {
        fund(sats = 5_000L)
        // Every answer of the first server is a 429 with a 600 s Retry-After. The scan must not wait for it.
        repeat(10) { first.queued.add(MockResponse.Builder().code(429).setHeader("Retry-After", "600").build()) }
        val scheduler = this.scheduler()
        val started = System.nanoTime()

        val snapshot = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1L)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5_000L, snapshot.confirmedSats, "the second server answers the whole scan")
        // Its lane allows four calls at once, and the first 429 cools it. No call is admitted to it after that, so it sees at most four.
        assertTrue(first.paths.size <= 4, "a cooled server gets no new call: it saw ${first.paths.size}")
        // The second server's own rate (5 calls a second, after a burst of 10) paces the scan's requests: about 10 s. A wait on the
        // 600 s header would fail this.
        assertTrue(elapsedMs < 30_000, "the scan must not wait out the Retry-After: it took $elapsedMs ms")
    }

    /** Both servers answer the same history for [address]: [sats] confirmed, in one used address. */
    private fun setStats(address: String, sats: Long) {
        for (server in listOf(first, second)) {
            server.stats[address] = addressJson(chain = side(fundedSum = sats, fundedCount = 1, txCount = 1))
        }
    }

    @Test
    fun aPaymentBelowAUsedAddressShowsOnceTheEarlierReadIsOlderThanTheTtl() = runBlocking<Unit> {
        setStats(account.receiveAddress(2), 10_000L)
        val scheduler = this.scheduler()
        val memo = ConcurrentHashMap<String, MemoStats>()
        val firstScan = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1_000L, memo = memo)
        assertEquals(10_000L, firstScan.confirmedSats)

        // A payment lands on receive address 1, below the used address 2. Within the TTL the walk still trusts its earlier read of address 1.
        setStats(account.receiveAddress(1), 7_000L)
        val within = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1_000L + DEFAULT_SHORT_TTL_MS - 1, memo = memo)
        assertEquals(10_000L, within.confirmedSats)

        // Once that read is a TTL old, the walk asks again, and the payment shows.
        val after = scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1_000L + DEFAULT_SHORT_TTL_MS, memo = memo)
        assertEquals(17_000L, after.confirmedSats)
    }

    @Test
    fun theOpenAccountReadsItsRecentTransactionsAndAPortfolioScanDoesNot() = runBlocking {
        fund(sats = 1_000L)
        val scheduler = this.scheduler()

        scanAccount(ref, scheduler, Priority.VISIBLE, nowMs = 1L, detail = false)
        assertFalse((first.paths + second.paths).any { it.endsWith("/txs") })

        scanAccount(ref, scheduler, Priority.FOREGROUND, nowMs = 2L, detail = true)
        assertTrue((first.paths + second.paths).any { it.endsWith("/txs") })
    }

    @Test
    fun aScanThatFailsMidwayKeepsWhatItHasRead() = runBlocking {
        // Two used receive addresses, then an address whose requests fail on both servers, so the receive walk ends there.
        for (server in listOf(first, second)) {
            server.stats[account.receiveAddress(0)] = addressJson(chain = side(fundedSum = 120_000L, fundedCount = 1, txCount = 1))
            server.stats[account.receiveAddress(2)] = addressJson(chain = side(fundedSum = 30_000L, fundedCount = 1, txCount = 1))
            server.failing += account.receiveAddress(4)
        }
        val store = BtcStore(Cache(FakeKvDao(), CacheKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }), FakeClock(1_000L)))
        val job = QueuedJob(1L, ScanJob("wallet-1", ref.account.network.networkId(), ref.key, Priority.VISIBLE))

        assertFailsWith<ProviderError.Transient> {
            scanJob(job, listOf(ref), this.scheduler(), store, AddressStatsCache(), nowMs = { 1_000L }, step = {})
        }

        val kept = store.snapshot(ref.key)
        assertEquals(150_000L, kept?.confirmedSats, "the two addresses read before the failure are kept")
        assertEquals(listOf(account.receiveAddress(0), account.receiveAddress(2)), kept?.addresses)
    }

    /** The receive addresses by index, for the walk tests that read no server. */
    private val receive: List<String> = List(150) { account.receiveAddress(it) }
    private val usedStats = EsploraParse.addressStats(addressJson(chain = side(fundedSum = 1_000L, fundedCount = 1, txCount = 1)))
    private val unusedStats = EsploraParse.addressStats(addressJson())

    /** The used indexes that [scanChain] finds, for [window], when the receive addresses in [used] have history and the others have none. */
    private suspend fun walkedUsed(used: Set<Int>, window: Int): List<Int> {
        val indexOf = receive.withIndex().associate { it.value to it.index }
        return scanChain(
            addressAt = { receive[it] },
            statsOf = { address -> if (indexOf.getValue(address) in used) usedStats else unusedStats },
            window = window,
        ).used.map { it.index }
    }

    /** The walk of a window of 1 and the walk of a whole gap must both find the used addresses that the one-at-a-time walk finds. */
    private suspend fun assertWalksAgree(used: Set<Int>) {
        val expected = sequentialWalk(used)
        for (window in listOf(1, BtcAccount.GAP_LIMIT)) {
            assertEquals(expected, walkedUsed(used, window), "used at $used, window $window")
        }
    }

    /** The walk that [scanChain] replaced: one address at a time, stopping after the gap limit. The windowed walk must find what it finds. */
    private fun sequentialWalk(used: Set<Int>): List<Int> {
        val found = mutableListOf<Int>()
        var unused = 0
        var index = 0
        while (unused < BtcAccount.GAP_LIMIT) {
            if (index in used) {
                found += index
                unused = 0
            } else {
                unused++
            }
            index++
        }
        return found
    }

    @Test
    fun theWindowedWalkFindsWhatTheOneAtATimeWalkFinds() = runBlocking {
        val fixtures = listOf(
            emptySet<Int>(),
            setOf(0),
            setOf(19),
            setOf(35),
            setOf(0, 20),
            setOf(0, 20, 40),
            setOf(0, 21),
            setOf(4, 23, 42),
        )
        for (used in fixtures) assertWalksAgree(used)
        // Random histories of every density, so the walk stops in many places: inside a window and at its edges.
        val random = Random(42)
        repeat(300) {
            val density = 1 + random.nextInt(30)
            assertWalksAgree((0 until 100).filter { random.nextInt(100) < density }.toSet())
        }
    }

    @Test
    fun aWalkOfOneAddressAtATimeReadsNoAddressPastTheStop() = runBlocking {
        val read = mutableListOf<Int>()
        scanChain(
            addressAt = { receive[it] },
            statsOf = { address ->
                val index = receive.indexOf(address)
                read += index
                if (index == 0) usedStats else unusedStats
            },
            window = 1,
        )
        // Index 0 is used, so the walk stops after the 20 unused addresses that follow it.
        assertEquals((0..20).toList(), read)
    }

    @Test
    fun aWindowIsAskedForAtOnce() = runBlocking {
        var inFlight = 0
        var mostInFlight = 0
        scanChain(
            addressAt = { receive[it] },
            statsOf = {
                inFlight++
                mostInFlight = maxOf(mostInFlight, inFlight)
                delay(5)
                inFlight--
                unusedStats
            },
        )
        assertTrue(mostInFlight > 1, "the reads of a window are in flight together: at most $mostInFlight at once")
    }

    @Test
    fun aFailedReadKeepsTheUsedAddressesBeforeItAndNoneAfter() = runBlocking {
        // Index 19 is used, and the read of 20, which opens the next window, fails. Index 25 may be read in that window,
        // but the walk never reaches it.
        val found = mutableListOf<UsedAddress>()
        assertFailsWith<ProviderError.Transient> {
            scanChain(
                addressAt = { receive[it] },
                statsOf = { address ->
                    when (receive.indexOf(address)) {
                        20 -> throw ProviderError.Transient()
                        19, 25 -> usedStats
                        else -> unusedStats
                    }
                },
                found = found,
            )
        }
        assertEquals(listOf(19), found.map { it.index })
    }

    /** The receive address of each scripthash, so that a fake Electrum server can answer by index. */
    private val indexOfScripthash: Map<String, Int> =
        receive.withIndex().associate { (index, address) -> scripthash(scriptOf(BtcNetwork.TESTNET4, address)) to index }

    /**
     * An Electrum server for the account's receive addresses. Index 0 holds 120,000 sats confirmed, in one unspent output. Index 2 holds 30,000 sats
     * unconfirmed, in one unspent output. [refused] names a method that the server answers with an error.
     */
    private fun electrumServer(refused: String? = null): FakeElectrum = FakeElectrum(TestIdentities.server, answer = { method, params ->
        val index = indexOfScripthash[params[0].jsonPrimitive.content]
        val txid = if (index == 0) "a".repeat(64) else "b".repeat(64)
        when (method) {
            refused -> Answer.Fail("refused")
            "blockchain.scripthash.get_history" -> Answer.Reply(
                JsonArray(
                    when (index) {
                        0 -> listOf(historyItem(txid, 850_000))
                        2 -> listOf(historyItem(txid, 0))
                        else -> emptyList()
                    },
                ),
            )
            "blockchain.scripthash.get_balance" -> Answer.Reply(
                buildJsonObject {
                    put("confirmed", JsonPrimitive(if (index == 0) 120_000L else 0L))
                    put("unconfirmed", JsonPrimitive(if (index == 2) 30_000L else 0L))
                },
            )
            "blockchain.scripthash.listunspent" -> Answer.Reply(
                JsonArray(
                    when (index) {
                        0 -> listOf(unspentItem(txid, vout = 0, height = 850_000, sats = 120_000L))
                        2 -> listOf(unspentItem(txid, vout = 1, height = 0, sats = 30_000L))
                        else -> emptyList()
                    },
                ),
            )
            else -> Answer.Fail("no such method: $method")
        }
    })

    private fun historyItem(txid: String, height: Int) = buildJsonObject {
        put("tx_hash", JsonPrimitive(txid))
        put("height", JsonPrimitive(height))
    }

    private fun unspentItem(txid: String, vout: Int, height: Int, sats: Long) = buildJsonObject {
        put("tx_hash", JsonPrimitive(txid))
        put("tx_pos", JsonPrimitive(vout))
        put("height", JsonPrimitive(height))
        put("value", JsonPrimitive(sats))
    }

    /** The used indexes that [electrumChain] finds when the receive addresses in [used] have history, and the number of batches it sent. */
    private suspend fun electrumWalk(used: Set<Int>): Pair<List<Int>, Int> {
        val indexOf = receive.withIndex().associate { it.value to it.index }
        var batches = 0
        val walk = electrumChain(
            addressAt = { receive[it] },
            read = { addresses ->
                batches++
                addresses.map { AddressReading(used = indexOf.getValue(it) in used, confirmedSats = 0L, unconfirmedSats = 0L) }
            },
        )
        return walk.used.map { it.index } to batches
    }

    @Test
    fun theElectrumWalkFindsWhatTheOneAtATimeWalkFinds() = runBlocking {
        val fixtures = listOf(
            emptySet<Int>(),
            setOf(0),
            setOf(19),
            setOf(35),
            setOf(0, 20),
            setOf(0, 20, 40),
            setOf(0, 21),
            setOf(4, 23, 42),
        )
        for (used in fixtures) assertEquals(sequentialWalk(used), electrumWalk(used).first, "used at $used")
        val random = Random(7)
        repeat(300) {
            val density = 1 + random.nextInt(30)
            val used = (0 until 100).filter { random.nextInt(100) < density }.toSet()
            assertEquals(sequentialWalk(used), electrumWalk(used).first, "used at $used")
        }
    }

    @Test
    fun aWindowOfTheElectrumWalkIsOneBatch() = runBlocking {
        // With no history the gap closes inside the first window of 20 addresses.
        assertEquals(1, electrumWalk(emptySet()).second)
        // Index 0 is used, so the gap runs on to index 20, which the second window holds.
        assertEquals(2, electrumWalk(setOf(0)).second)
    }

    @Test
    fun electrumReadsTheWalkAndTheCoinsWhileEsploraAnswersNoAddress() = runBlocking {
        electrumServer().use { fake ->
            val pool = ElectrumPool(servers = listOf(ElectrumServer("localhost", Trust.Pinned(TestIdentities.server.spkiSha256), port = fake.port)))

            val snapshot = scanAccount(ref, this.scheduler(), Priority.VISIBLE, nowMs = 1L, electrum = pool)

            assertEquals(120_000L, snapshot.confirmedSats)
            assertEquals(30_000L, snapshot.unconfirmedSats)
            assertEquals(listOf(receive[0], receive[2]), snapshot.addresses)
            assertEquals(1, snapshot.nextReceiveIndex)
            assertEquals(listOf(BtcUtxo("a".repeat(64), 0, 120_000L, 850_000L), BtcUtxo("b".repeat(64), 1, 30_000L, null)), snapshot.utxos)
            assertTrue(first.statsRequests().isEmpty() && second.statsRequests().isEmpty(), "Electrum answered the walk; Esplora was asked no address")
        }
    }

    @Test
    fun aPoolThatCannotConnectIsReadFromEsplora() = runBlocking {
        fund(sats = 120_000L)
        val unreachable = ElectrumPool(
            servers = listOf(ElectrumServer("localhost", Trust.Authority)),
            open = { _ -> throw ElectrumError.Transport("refused") },
        )

        val snapshot = scanAccount(ref, this.scheduler(), Priority.VISIBLE, nowMs = 1L, electrum = unreachable)

        assertEquals(120_000L, snapshot.confirmedSats)
        assertEquals(1, snapshot.utxos.size)
        assertTrue(first.statsRequests().isNotEmpty(), "Esplora read the account")
    }

    @Test
    fun aServerThatRefusesABatchIsReadFromEsplora() = runBlocking {
        fund(sats = 120_000L)
        electrumServer(refused = "blockchain.scripthash.get_history").use { fake ->
            val pool = ElectrumPool(servers = listOf(ElectrumServer("localhost", Trust.Pinned(TestIdentities.server.spkiSha256), port = fake.port)))

            val snapshot = scanAccount(ref, this.scheduler(), Priority.VISIBLE, nowMs = 1L, electrum = pool)

            assertEquals(120_000L, snapshot.confirmedSats)
            assertEquals(1, snapshot.utxos.size)
            assertTrue(first.statsRequests().isNotEmpty(), "Esplora read the account")
        }
    }

    @Test
    fun thePublicElectrumServersAreNotUsedForAnOwnerServerOrForTestnet() {
        val pool = ElectrumPool(servers = emptyList())
        assertSame(pool, publicElectrumFor(pool, BtcNetwork.MAINNET, owned = emptySet()))
        assertNull(publicElectrumFor(pool, BtcNetwork.MAINNET, owned = setOf(BtcNetwork.MAINNET)))
        assertNull(publicElectrumFor(pool, BtcNetwork.TESTNET4, owned = emptySet()))
    }
}
