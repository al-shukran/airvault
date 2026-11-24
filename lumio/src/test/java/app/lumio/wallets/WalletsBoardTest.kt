package app.lumio.wallets

import android.os.Looper
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletPad
import app.lumio.LumioApp
import app.lumio.removeWalletHistory
import app.lumio.LumioVault
import app.lumio.data.asset.AssetId
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.jsonCodec
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.Kept
import app.lumio.data.price.MARKET_KEY
import app.lumio.data.price.PriceRepository
import app.lumio.data.price.Price
import app.lumio.data.provider.MarketQuote
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairImport
import app.lumio.pair.PairedWallets
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WalletWatchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistStore
import com.sparrowwallet.hummingbird.UR
import java.io.File
import java.math.BigInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The wallet board holds each account's rows before its sheet opens. Opening the board reads the account's tokens, its prices and its newest activity
 * from the vault's caches and store, so the Account screen has its rows on the first frame, and no network read is needed for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class WalletsBoardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mode = NetworkMode.TESTNET

    @Test
    fun theBoardHoldsAnEthereumAccountsRowsBeforeItsSheetOpens() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "board.db"))
        val vault = LumioVault(session, database)
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        runBlocking { vault.txs.put(listOf(transfer(wallet.evmAddresses.single()))) }

        // The account has no balance in the caches, so no asset is priced and no price source is asked.
        val board = WalletsBoard(RuntimeEnvironment.getApplication() as LumioApp, vault) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)
        val account = await { board.view?.accountsOf(wallet.id)?.singleOrNull { it.namespace == Networks.EIP155 } }
        val reads = board.readsOf(account, mode)

        val activity = await { reads.activity }
        assertEquals(listOf(TX_ID), activity.map { it.tx.id })
        assertEquals(emptyList<Any>(), reads.tokens)

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun theBoardShowsTheLastMarketAndTheWatchlistKeptInTheVaultBeforeAnyNetworkRead() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "market.db"))
        val vault = LumioVault(session, database)
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        val kept = MarketQuote(totalUsd = 2.6e12, change24hPct = 2.14, asOfMs = 1_000L)
        runBlocking { vault.cache.put(CachePolicy.LastKnown, MARKET_KEY, jsonCodec<MarketQuote>().encode(kept)) }

        // No source is asked: the market and the watchlist come from the vault, so Home shows them on its first frame.
        val app = RuntimeEnvironment.getApplication() as LumioApp
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)

        assertEquals(Kept(kept, stale = true), await { board.market })
        assertEquals(WalletWatchlist(assets = FIRST_RUN_ASSETS), await { board.watchlists[wallet.id] })

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun aWalletPairedAgainAfterItsDeletionDoesNotShowTheDeletedWatchlist() {
        // A padded vault, as production keeps one: a deleted wallet's slot is the one the next pairing takes, so the id comes back.
        val layout = VaultLayout(tmp.root)
        val keys = MemoryKeystore()
        val key = ByteArray(32) { (it * 3 + 2).toByte() }
        val session = VaultSession(layout, keys, key, pad = WalletPad(layout, keys, key.copyOf(), section = 0))
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "repair.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        val starred = WalletWatchlist(assets = listOf(WatchedAsset(NetworkId(Networks.evm(10143), testnet = true))))
        WatchlistStore.write(session, wallet.id, starred)
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        vault.wallets = board
        board.showSide(mode, listVersion = 0)
        assertEquals(starred, await { board.watchlists[wallet.id] })

        // The wallet is deleted, then paired again under the same id. Its list must not come back from the board's memory.
        PairedWallets.delete(session, wallet.id)
        runBlocking { removeWalletHistory(vault, wallet) }
        PairedWallets.save(session, pairing(), nowMs = 200L)
        assertEquals(wallet.id, PairedWallets.list(session).single().id)
        board.showSide(mode, listVersion = 1)

        assertEquals(WalletWatchlist(assets = FIRST_RUN_ASSETS), await { board.watchlists[wallet.id] })

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun aReadInFlightWhenItsWalletIsDeletedDoesNotBringTheWatchlistBack() {
        // A padded vault, as production keeps one, so the deleted wallet's id is the one a later pairing could take.
        val layout = VaultLayout(tmp.root)
        val keys = MemoryKeystore()
        val key = ByteArray(32) { (it * 11 + 5).toByte() }
        val session = VaultSession(layout, keys, key, pad = WalletPad(layout, keys, key.copyOf(), section = 0))
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "inflight.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        WatchlistStore.write(session, wallet.id, WalletWatchlist(assets = listOf(WatchedAsset(NetworkId(Networks.evm(10143), testnet = true)))))
        // The board's first read holds at its price read until the test releases it, so the deletion lands while the read is in flight.
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holding = true
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { _ ->
            if (holding) {
                holding = false
                started.complete(Unit)
                release.await()
            }
            emptyMap<AssetId, Price>()
        }
        vault.wallets = board
        board.showSide(mode, listVersion = 0)
        await { if (started.isCompleted) Unit else null }

        PairedWallets.delete(session, wallet.id)
        runBlocking { removeWalletHistory(vault, wallet) }
        release.complete(Unit)
        settle()

        assertNull(board.watchlists[wallet.id])

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun aDeletionOnAWorkerThreadChangesTheBoardOnlyWhenTheMainThreadRunsIt() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 13 + 7).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "worker.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        val starred = WalletWatchlist(assets = listOf(WatchedAsset(NetworkId(Networks.evm(10143), testnet = true))))
        WatchlistStore.write(session, wallet.id, starred)
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        vault.wallets = board
        board.showSide(mode, listVersion = 0)
        assertEquals(starred, await { board.watchlists[wallet.id] })

        // The vault's deletion runs on Dispatchers.IO, so the forget comes from a worker thread. The board's state is the main thread's, so the worker
        // must leave the change to Main: the list stays until the main looper runs.
        thread { runBlocking { removeWalletHistory(vault, wallet) } }.join()
        assertEquals(starred, board.watchlists[wallet.id])

        shadowOf(Looper.getMainLooper()).idle()
        assertNull(board.watchlists[wallet.id])

        board.close()
        database.close()
        session.close()
    }

    /** A paired wallet with one Ethereum account, as the pairing carries it. */
    private fun pairing(): PairingPayload {
        val payload = PairingPayload(
            fingerprint = 0x5436D724L,
            name = "Demo",
            autoNameBase = "Demo",
            accounts = listOf(PairFixtures.ethereumAccount(0, "Main")),
        )
        return PairImport.of(UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, null)))
    }

    private fun transfer(address: String) = LumioTx(
        id = TX_ID,
        network = NetworkId(Networks.evm(10143), testnet = true),
        account = address,
        hash = "0x01",
        timestampMs = 1_000L,
        asset = TxAsset(contract = null, symbol = "MON", name = "Monad", decimals = 18),
        amount = BigInteger.ONE,
        direction = TxDirection.IN,
        fee = null,
        counterparty = null,
        status = TxStatus.CONFIRMED,
        confirmations = 1L,
        inputData = null,
        memo = null,
    )

    /** Runs the main looper until [read] has a value, since the board shows its reads on the main thread and reads the vault off it. */
    private fun <T : Any> await(read: () -> T?): T {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            read()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "the board did not hold the account in time" }
            Thread.sleep(5)
        }
    }

    /** Runs the main looper for a while, for a read that resumes on the main thread after its reads return, and has no value to wait for. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private companion object {
        val ADDRESS = "0x" + "a1".repeat(20)
        const val TX_ID = "EVM:10143:tx1"
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_ROUNDS = 50
    }
}
