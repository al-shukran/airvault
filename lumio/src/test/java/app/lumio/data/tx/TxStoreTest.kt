package app.lumio.data.tx

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.ETH
import app.lumio.data.containsBytes
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The transaction store on Robolectric, with a real Room database file and keys derived from a vault session. The fixture is eight transactions across
 * two accounts, one of them spam and one pending, so each filter, sort and search has rows that it must include and rows that it must leave out.
 */
@RunWith(RobolectricTestRunner::class)
class TxStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LumioDatabase
    private lateinit var store: TxStore

    private val me = "0x" + "ab".repeat(20)
    private val other = "0x" + "77".repeat(20)
    private val bob = "0x" + "cd".repeat(20)
    private val carol = "0x" + "ef".repeat(20)
    private val spamSender = "0x" + "56".repeat(20)
    private val usdc = "0x" + "ee".repeat(20)
    private val native = TxAsset(null, "ETH", "Ether", 18)
    private val usdcAsset = TxAsset(usdc, "USDC", "USD Coin", 6)
    private val btc = NetworkId(Family.BTC, "mainnet", testnet = false)
    private val btcAccount = "bc1qmine"
    private val oneEth: BigInteger = BigInteger.TEN.pow(18)
    private val fee: BigInteger = BigInteger.valueOf(21_000L) * BigInteger.valueOf(20_000_000_000L)

    @Before
    fun open() {
        database = LumioDatabase.open(context, File(tmp.root, "tx.db"))
        store = TxStore(database.txs(), keys())
    }

    @After
    fun close() {
        database.close()
    }

    private fun keys(seed: Int = 1): TxKeys =
        TxKeys.from(VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + seed).toByte() }))

    private fun hashOf(n: Int): String = "0x" + n.toString(16).padStart(64, '0')

    private fun tx(
        n: Int,
        timestampMs: Long,
        amount: BigInteger,
        direction: TxDirection = TxDirection.IN,
        asset: TxAsset = native,
        counterparty: String? = bob,
        status: TxStatus = TxStatus.CONFIRMED,
        confirmations: Long = 1_000L,
        fee: BigInteger? = null,
        memo: String? = null,
        spam: Boolean = false,
        account: String = me,
    ): LumioTx = LumioTx(
        id = "EVM:1:${hashOf(n)}:${asset.contract ?: "native"}",
        network = ETH,
        account = account,
        hash = hashOf(n),
        timestampMs = timestampMs,
        asset = asset,
        amount = amount,
        direction = direction,
        fee = fee,
        counterparty = counterparty,
        status = status,
        confirmations = confirmations,
        inputData = null,
        memo = memo,
        spam = spam,
    )

    /** The eight transactions. Their timestamps are 1000 to 8000, so the newest first order is 7, 6, 4, 2, 3, 1, with 5 spam and 8 on another account. */
    private val rows: List<LumioTx> = listOf(
        tx(1, 1_000L, oneEth.multiply(BigInteger.valueOf(2)), memo = "Rent for March"),
        tx(2, 3_000L, oneEth, direction = TxDirection.OUT, counterparty = carol, fee = fee),
        tx(3, 2_000L, BigInteger.valueOf(500_000_000L), asset = usdcAsset, counterparty = carol),
        tx(4, 4_000L, BigInteger.ZERO, direction = TxDirection.OUT, status = TxStatus.FAILED, fee = BigInteger.valueOf(300_000_000_000_000L)),
        tx(5, 5_000L, BigInteger.ZERO, asset = usdcAsset, counterparty = spamSender, spam = true),
        tx(6, 6_000L, oneEth.divide(BigInteger.valueOf(2)), direction = TxDirection.SELF, counterparty = null),
        tx(7, 7_000L, oneEth.divide(BigInteger.TEN), status = TxStatus.PENDING, confirmations = 0L),
        tx(8, 8_000L, oneEth, account = other),
    )

    private fun seed() = runBlocking { store.put(rows) }

    private fun query(q: TxQuery): List<LumioTx> = runBlocking { store.page(q, 0, 100) }

    /** The transaction numbers of [txs], from the hash that [hashOf] made. */
    private fun List<LumioTx>.numbers(): List<Int> = map { it.hash.removePrefix("0x").trimStart('0').toInt(16) }

    @Test
    fun aStoredTransactionReadsBackAsItWasWritten() = runBlocking {
        store.put(listOf(rows[0]))

        assertEquals(rows[0], store.page(TxQuery(accounts = setOf(me)), 0, 10).single())
    }

    @Test
    fun theDefaultOrderIsNewestFirstAndSpamIsLeftOut() {
        seed()

        assertEquals(listOf(7, 6, 4, 2, 3, 1), query(TxQuery(accounts = setOf(me))).numbers())
        assertEquals(6, runBlocking { store.count(TxQuery(accounts = setOf(me))) })
        assertEquals(7, runBlocking { store.count(TxQuery(accounts = setOf(me), includeSpam = true)) })
    }

    @Test
    fun aSearchFindsTheHashTheCounterpartyTheMemoTheAssetAndTheComment() {
        seed()

        assertEquals(listOf(2), query(TxQuery(accounts = setOf(me), text = hashOf(2))).numbers())
        assertEquals(listOf(2, 3), query(TxQuery(accounts = setOf(me), text = carol)).numbers())
        assertEquals(listOf(1), query(TxQuery(accounts = setOf(me), text = "march")).numbers())
        assertEquals(listOf(3), query(TxQuery(accounts = setOf(me), text = "usdc")).numbers())
        assertEquals(listOf(1), query(TxQuery(accounts = setOf(me), text = "MARCH rent")).numbers())
        assertEquals(emptyList(), query(TxQuery(accounts = setOf(me), text = "rent deposit")).numbers())
        runBlocking { store.setComment(rows[1].id, "paid the deposit", 9_000L) }
        assertEquals(listOf(2), query(TxQuery(accounts = setOf(me), text = "deposit")).numbers())
    }

    @Test
    fun filtersNarrowByAccountNetworkAssetTimeDirectionStatusAndAmount() {
        seed()

        assertEquals(listOf(8), query(TxQuery(accounts = setOf(other))).numbers())
        assertEquals(listOf(3), query(TxQuery(accounts = setOf(me), assets = setOf(TxAssetRef(ETH, usdc)))).numbers())
        assertEquals(listOf(2, 3), query(TxQuery(accounts = setOf(me), fromMs = 2_000L, untilMs = 4_000L)).numbers())
        assertEquals(listOf(4, 2), query(TxQuery(accounts = setOf(me), directions = setOf(TxDirection.OUT))).numbers())
        assertEquals(listOf(4), query(TxQuery(accounts = setOf(me), statuses = setOf(TxStatus.FAILED))).numbers())
        assertEquals(listOf(7), query(TxQuery(accounts = setOf(me), statuses = setOf(TxStatus.PENDING))).numbers())
        assertEquals(listOf(2, 3, 1), query(TxQuery(accounts = setOf(me), amountAbove = BigDecimal("0.5"))).numbers())
        assertEquals(listOf(7, 4), query(TxQuery(accounts = setOf(me), amountBelow = BigDecimal("0.5"))).numbers())
    }

    @Test
    fun everySortableFieldSortsInBothDirections() {
        seed()
        fun ids(sort: TxSort, descending: Boolean) = query(TxQuery(accounts = setOf(me), sort = sort, descending = descending)).numbers()

        assertEquals(listOf(1, 3, 2, 4, 6, 7), ids(TxSort.TIMESTAMP, descending = false))
        assertEquals(listOf(3, 1, 2, 6, 7, 4), ids(TxSort.AMOUNT, descending = true))
        assertEquals(listOf(4, 7, 6, 2, 1, 3), ids(TxSort.AMOUNT, descending = false))
        assertEquals(listOf(2, 4), ids(TxSort.FEE, descending = true).take(2))
        assertEquals(3, ids(TxSort.ASSET, descending = false).last())
        assertEquals(6, ids(TxSort.DIRECTION, descending = false).last())
        assertEquals(7, ids(TxSort.STATUS, descending = true).first())
    }

    @Test
    fun pagesCoverTheWholeResultOnceInOrder() {
        seed()
        val q = TxQuery(accounts = setOf(me), sort = TxSort.AMOUNT)

        val all = query(q).numbers()
        val paged = listOf(0, 2, 4).flatMap { offset -> runBlocking { store.page(q, offset, 2) }.numbers() }

        assertEquals(all, paged)
        assertEquals(all.size, runBlocking { store.count(q) })
    }

    @Test
    fun aSettledTransactionIsNeverReplaced() {
        runBlocking { store.put(listOf(rows[1])) }

        runBlocking { store.put(listOf(rows[1].copy(amount = BigInteger.ONE))) }

        assertEquals(oneEth, query(TxQuery(accounts = setOf(me))).single().amount)
    }

    @Test
    fun aPendingTransactionIsReplacedByItsConfirmedForm() {
        runBlocking { store.put(listOf(rows[6])) }

        runBlocking { store.put(listOf(rows[6].copy(status = TxStatus.CONFIRMED, confirmations = 2L))) }

        assertEquals(TxStatus.CONFIRMED, query(TxQuery(accounts = setOf(me))).single().status)
    }

    @Test
    fun droppingPendingRemovesOnlyThePendingRowsTheNetworkNoLongerLists() = runBlocking {
        fun btcTx(name: String, status: TxStatus, confirmations: Long) = LumioTx(
            id = "BTC:mainnet:$name",
            network = btc,
            account = btcAccount,
            hash = name,
            timestampMs = 1_000L,
            asset = BTC_ASSET,
            amount = BigInteger.valueOf(5_000L),
            direction = TxDirection.IN,
            fee = null,
            counterparty = "bc1qother",
            status = status,
            confirmations = confirmations,
            inputData = null,
            memo = null,
        )
        store.put(listOf(btcTx("aa", TxStatus.PENDING, 0L), btcTx("bb", TxStatus.PENDING, 0L), btcTx("cc", TxStatus.CONFIRMED, 10L)))

        store.dropPending(btcAccount, btc, keep = setOf("BTC:mainnet:bb"))

        assertEquals(setOf("bb", "cc"), store.page(TxQuery(accounts = setOf(btcAccount)), 0, 10).map { it.hash }.toSet())
    }

    @Test
    fun counterpartiesAreTheNonSpamAddressesTheAccountHasDealtWith() {
        seed()

        assertEquals(setOf(bob, carol), runBlocking { store.counterparties(me) })
    }

    @Test
    fun aCommentIsStoredSearchedAndClearedWithoutEviction() {
        seed()
        runBlocking { store.setComment(rows[1].id, "paid the deposit", 9_000L) }

        assertEquals(TxNote(rows[1].id, "paid the deposit", 9_000L), runBlocking { store.comment(rows[1].id) })
        assertEquals(1, runBlocking { store.comments() }.size)
        runBlocking { store.clearComment(rows[1].id) }

        assertNull(runBlocking { store.comment(rows[1].id) })
        assertEquals(emptyList(), query(TxQuery(accounts = setOf(me), text = "deposit")).numbers())
    }

    @Test
    fun theDatabaseFileHoldsNoHashAddressMemoOrCommentInTheClear() {
        seed()
        runBlocking { store.setComment(rows[0].id, "Paid the landlord", 9_000L) }
        database.close()

        val bytes = File(tmp.root, "tx.db").readBytes()
        listOf(hashOf(1), bob, carol, "Rent for March", "Paid the landlord").forEach { secret ->
            assertFalse(bytes.containsBytes(secret.toByteArray()), "$secret must not be in the file in clear")
        }
    }
}
