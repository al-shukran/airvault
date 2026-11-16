package app.lumio.data.tx

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.btc.EsploraInput
import app.lumio.btc.EsploraOutput
import app.lumio.btc.EsploraPrevout
import app.lumio.btc.EsploraTx
import app.lumio.data.ETH
import app.lumio.data.FakeClock
import app.lumio.data.FakeProvider
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.Op
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.explorer.ExplorerTx
import app.lumio.data.provider.Trait
import app.lumio.data.sched.Scheduler
import java.io.File
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The refresh of a history: the providers' answers are mapped, flagged by the spam heuristic against what the store already holds, and stored. The
 * providers are fakes that answer in the shapes of the explorers and of Esplora.
 */
@RunWith(RobolectricTestRunner::class)
class TxHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LumioDatabase
    private lateinit var store: TxStore
    private val clock = FakeClock(0L)

    private val me = "0x" + "ab".repeat(20)
    private val usdc = "0x" + "ee".repeat(20)
    private val listed = "0x" + "aa".repeat(20)
    private val friend = "0x1234" + "a".repeat(32) + "9f9f"
    private val lookalike = "0x1234" + "b".repeat(32) + "9f9f"
    private val hash1 = "0x" + "1a".repeat(32)
    private val hash2 = "0x" + "2b".repeat(32)
    private val native = TxAsset(null, "ETH", "Ether", 18)
    private val btc = NetworkId(Family.BTC, "mainnet", testnet = false)
    private val btcAccount = "bc1qmyaccountaddress12345"

    @Before
    fun open() {
        database = LumioDatabase.open(context, File(tmp.root, "history.db"))
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() })
        store = TxStore(database.txs(), TxKeys.from(session))
    }

    @After
    fun close() {
        database.close()
    }

    private fun scheduler(scope: CoroutineScope, vararg providers: FakeProvider): Scheduler = Scheduler(
        providers = providers.toList(),
        configs = { emptyMap() },
        http = Http(OkHttpClient()),
        clock = clock,
        random = Random(7),
        scope = scope,
    )

    private fun history(scheduler: Scheduler, preferRpc: Boolean = false) = TxHistory(
        scheduler = scheduler,
        store = store,
        spam = TxSpam { _, contract -> contract == listed },
        nativeAsset = { if (it.family == Family.EVM) native else null },
        preferRpc = { preferRpc },
        clock = clock,
    )

    private fun explorer() = FakeProvider("explorer", traits = setOf(Trait.KEYLESS), networks = setOf(ETH), ops = setOf(Op.TX_HISTORY))

    private fun normal(hash: String, from: String, to: String) = ExplorerTx.Normal(
        hash = hash,
        timestampSec = 1_693_526_411L,
        from = from,
        to = to,
        confirmations = 1_000L,
        valueWei = BigInteger.TEN.pow(18),
        failed = false,
        gasUsed = BigInteger.valueOf(21_000L),
        gasPrice = BigInteger.valueOf(20_000_000_000L),
        input = "0x",
        createdContract = null,
    )

    private fun token(hash: String, from: String, to: String, amount: BigInteger) = ExplorerTx.Token(
        hash = hash,
        timestampSec = 1_693_526_411L,
        from = from,
        to = to,
        confirmations = 900L,
        contract = usdc,
        name = "USD Coin",
        symbol = "USDC",
        decimals = 6,
        amount = amount,
        ordinal = 0,
    )

    @Test
    fun anEvmHistoryIsStoredAndATransferOfAnUnlistedTokenFromALookalikeIsFlaggedAsSpam() = runBlocking {
        val explorer = explorer()
        explorer.outcomes.add(listOf<ExplorerTx>(normal(hash1, me, friend)))
        val history = history(scheduler(this, explorer))
        assertEquals(1, history.refresh(ETH, me))

        explorer.outcomes.add(listOf<ExplorerTx>(token(hash2, lookalike, me, BigInteger.ZERO)))
        assertEquals(1, history.refresh(ETH, me))

        assertEquals(listOf(hash1), store.page(TxQuery(account = me), 0, 10).map { it.hash })
        assertEquals(2, store.count(TxQuery(account = me, includeSpam = true)))
    }

    @Test
    fun withPreferRpcOnNoExplorerIsAskedAndTheHistoryIsRefused() = runBlocking {
        val explorer = explorer()

        assertFailsWith<ProviderError.Unsupported> { history(scheduler(this, explorer), preferRpc = true).refresh(ETH, me) }
        assertEquals(0, explorer.calls)
    }

    @Test
    fun aBitcoinRefreshDropsThePendingRowsTheListNoLongerHas() = runBlocking {
        val esplora = FakeProvider("esplora", traits = setOf(Trait.KEYLESS), networks = setOf(btc), ops = setOf(Op.ADDRESS_TXS, Op.TIP_HEIGHT))
        val pending = esploraTx("aa", blockHeight = null, blockTimeSec = null)
        val confirmed = esploraTx("cc", blockHeight = 799_990L, blockTimeSec = 1_699_999_000L)
        esplora.outcomes.add(800_000L)
        esplora.outcomes.add(listOf(pending, confirmed))
        esplora.outcomes.add(800_000L)
        esplora.outcomes.add(listOf(confirmed))
        val history = history(scheduler(this, esplora))

        assertEquals(2, history.refresh(btc, btcAccount))
        assertEquals(1, history.refresh(btc, btcAccount))

        assertEquals(listOf("cc".repeat(32)), store.page(TxQuery(account = btcAccount), 0, 10).map { it.hash })
    }

    @Test
    fun aBitcoinScanStoresTheTransactionsItReadForTheAddress() = runBlocking {
        val history = history(scheduler(this))
        val spent = esploraTx("dd", blockHeight = 799_990L, blockTimeSec = 1_699_999_000L)

        assertEquals(1, history.storeBitcoin(btc, btcAccount, listOf(spent), tipHeight = 800_000L))

        assertEquals(listOf("dd".repeat(32)), store.page(TxQuery(account = btcAccount), 0, 10).map { it.hash })
    }

    @Test
    fun theTransactionOfAStoredRowIsReadBackByItsIdAndAccount() = runBlocking {
        val history = history(scheduler(this))
        val spent = esploraTx("ee", blockHeight = 799_990L, blockTimeSec = 1_699_999_000L)
        history.storeBitcoin(btc, btcAccount, listOf(spent), tipHeight = 800_000L)
        val stored = store.page(TxQuery(account = btcAccount), 0, 10).single()

        assertEquals(stored, store.tx(btcAccount, stored.id))
        assertNull(store.tx("bc1qsomeoneelse", stored.id))
    }

    private fun esploraTx(name: String, blockHeight: Long?, blockTimeSec: Long?) = EsploraTx(
        txid = name.repeat(32),
        inputs = listOf(EsploraInput(EsploraPrevout("bc1qother", 50_000L))),
        outputs = listOf(EsploraOutput(btcAccount, 40_000L)),
        blockHeight = blockHeight,
        blockTimeSec = blockTimeSec,
    )
}
