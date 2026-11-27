package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.btc.BtcSnapshot
import app.lumio.data.cache.CachePolicy
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.scan.ScanJob
import app.lumio.data.scan.ScanQueue
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.ui.receive.receiveShareDir
import java.math.BigInteger
import app.lumio.data.sched.Priority
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The database holds the real vault's rows, which the promoted vault's key cannot read. Unlocking with the Duress Password erases the
 * real vault, so opening the promoted session through [LumioApp] runs the purge of unreadable rows, and the real wallets' cache, scans,
 * queued jobs, transactions, search terms and comments go with it, and so does the receive code that the real vault shared. The rows of the
 * promoted vault itself are kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class DuressUnlockClearsRealRowsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: LumioApp get() = RuntimeEnvironment.getApplication() as LumioApp

    @Test
    fun theDuressUnlockClearsTheRealRowsAndKeepsThePromotedVaultsOwn() = runBlocking {
        val clock = FakeClock()
        val vault = Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))
        assertEquals(CreateResult.Created, vault.create(REAL.toCharArray()))
        val real = unlocked(vault.unlock(REAL.toCharArray()))
        val holder = VaultHolder(vault)
        holder.adopt(real, holder.unlockEpoch())
        // The decoy that the promotion makes the vault has rows of its own, which the real vault's purge must leave alone.
        val seedDatabase = LumioDatabase.open(app, lumioDatabaseFile(VaultLayout.of(app)))
        assertEquals(
            DuressResult.Enabled,
            holder.vault.setDuress(holder.openSession()!!, DURESS.toCharArray()) { decoy ->
                decoy.createWallet()
                runBlocking { seed(LumioVault(decoy, seedDatabase), DECOY_TX, "decoy memo", "3500") }
            },
        )
        seedDatabase.close()
        holder.lock()
        clock.advance(ONE_DAY_MS)
        val realSession = unlocked(vault.unlock(REAL.toCharArray()))
        clock.advance(ONE_DAY_MS)

        // The real rows go in through a vault of their own, so no purge of the app's runs against them while they are seeded.
        val database = LumioDatabase.open(app, lumioDatabaseFile(VaultLayout.of(app)))
        val seeded = LumioVault(realSession, database)
        seed(seeded, REAL_TX, "real memo", "3000")
        seeded.store.save(SNAPSHOT)
        ScanQueue { seeded.scanRows }.enqueue(JOB)
        File(receiveShareDir(app).apply { mkdirs() }, "receive-qr.png").writeBytes(byteArrayOf(1, 2, 3))
        val reopenedBefore = LumioVault(realSession, database)
        assertNotNull(reopenedBefore.cache.get(CachePolicy.LastKnown, "native-price:ETH"), "The rows are readable before the unlock.")
        assertEquals(REAL_TX, reopenedBefore.txs.tx(REAL_TX.account, REAL_TX.id), "The real transaction is readable before the unlock.")
        assertEquals(2, rowsIn(database, "tx"), "The real and the decoy transaction are in the table.")

        clock.advance(ONE_DAY_MS)
        val promoted = unlocked(vault.unlock(DURESS.toCharArray()))
        assertEquals(1, promoted.side)
        val appHolder = app.vaultHolder
        appHolder.adopt(promoted, appHolder.unlockEpoch())
        // The purge runs on the app's own scope, so it shows only after the scope has had time to run it.
        delay(SETTLE_MS)

        val reopened = LumioVault(realSession, database)
        assertNull(reopened.cache.get(CachePolicy.LastKnown, "native-price:ETH"), "The real cache entry goes.")
        assertNull(reopened.store.snapshot(SNAPSHOT.key), "The real scan goes.")
        assertEquals(emptyList(), ScanQueue { reopened.scanRows }.pending().map { it.job }, "The real queued job goes.")
        assertNull(reopened.txs.tx(REAL_TX.account, REAL_TX.id), "The real transaction goes.")
        assertNull(reopened.txs.comment(REAL_TX.id), "The real comment goes.")
        assertEquals(1, rowsIn(database, "tx"), "Only the promoted vault's transaction row is left, with none of the real vault's plain columns.")
        assertEquals(1, rowsIn(database, "tx_comment"), "Only the promoted vault's comment is left.")
        assertEquals(
            0,
            rowsIn(database, "tx_term WHERE txId NOT IN (SELECT txId FROM tx UNION SELECT txId FROM tx_comment)"),
            "No search term of the real vault is left.",
        )
        assertFalse(File(receiveShareDir(app), "receive-qr.png").exists(), "The receive code of the real vault goes.")

        // What the promoted vault wrote for itself is still there.
        val kept = app.lumioVault()!!
        assertEquals("3500", kept.cache.get(CachePolicy.LastKnown, "native-price:ETH")?.value?.decodeToString(), "The promoted cache entry stays.")
        assertEquals(DECOY_TX, kept.txs.tx(DECOY_TX.account, DECOY_TX.id), "The promoted transaction stays.")
        assertEquals("decoy memo", kept.txs.comment(DECOY_TX.id)?.text, "The promoted comment stays.")
        assertEquals(1, ScanQueue { kept.scanRows }.pending().size, "The promoted queued job stays.")
        appHolder.lock()
        realSession.close()
        database.close()
    }

    /** Writes a cache entry, a transaction with its comment, and (for the decoy) a queued scan job into [target]. */
    private suspend fun seed(target: LumioVault, tx: LumioTx, comment: String, price: String) {
        target.cache.put(CachePolicy.LastKnown, "native-price:ETH", price.toByteArray())
        target.txs.put(listOf(tx))
        target.txs.setComment(tx.id, comment, 1L)
        if (tx === DECOY_TX) ScanQueue { target.scanRows }.enqueue(JOB)
    }

    /** The number of rows that the raw SQL [from] names, whatever key they are sealed under. */
    private fun rowsIn(database: LumioDatabase, from: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $from").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertIs<UnlockResult.Unlocked>(result)
        return result.session
    }

    private companion object {
        const val REAL = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        const val SETTLE_MS = 1_500L
        val ETH_NET = NetworkId(Networks.evm(1), testnet = false)
        val REAL_TX = txOf(1, "0x" + "ab".repeat(20))
        val DECOY_TX = txOf(2, "0x" + "cd".repeat(20))

        fun txOf(n: Int, account: String) = LumioTx(
            id = "EVM:1:0x$n:native",
            network = ETH_NET,
            account = account,
            hash = "0x$n",
            timestampMs = 1_000L * n,
            asset = TxAsset(null, "ETH", "Ether", 18),
            amount = BigInteger.TEN.pow(18),
            direction = TxDirection.IN,
            fee = null,
            counterparty = "0x" + "ef".repeat(20),
            status = TxStatus.CONFIRMED,
            confirmations = 1_000L,
            inputData = null,
            memo = "memo $n",
        )
        val JOB = ScanJob("wallet-1", NetworkId(Networks.BTC_TESTNET4, testnet = true), SNAPSHOT_KEY, Priority.VISIBLE)
        const val SNAPSHOT_KEY = "wallet-1_TESTNET4_NATIVE_SEGWIT"
        val SNAPSHOT = BtcSnapshot(
            key = SNAPSHOT_KEY,
            identity = "tb1qidentity",
            scannedAtMs = 1L,
            tipHeight = 1L,
            confirmedSats = 5L,
            unconfirmedSats = 0L,
            nextReceiveIndex = 0,
            utxos = emptyList(),
            transactions = emptyList(),
        )
    }
}
