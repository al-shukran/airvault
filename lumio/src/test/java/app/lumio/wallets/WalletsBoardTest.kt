package app.lumio.wallets

import android.os.Looper
import app.airvault.core.model.ChainId
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
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.PriceRepository
import app.lumio.data.price.Price
import app.lumio.data.scan.ScanEvent
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairImport
import app.lumio.pair.PairedWallets
import app.lumio.tokens.CachedTokenBalance
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.Token
import app.lumio.tokens.TokenBalanceCache
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.Watchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import app.lumio.watchlist.WatchlistStore
import app.lumio.watchlist.toggled
import com.sparrowwallet.hummingbird.UR
import java.io.File
import java.math.BigInteger
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
    fun theBoardShowsTheWatchlistKeptInTheVaultBeforeAnyNetworkRead() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "market.db"))
        val vault = LumioVault(session, database)
        PairedWallets.save(session, pairing(), nowMs = 100L)
        // No source is asked: the watchlist comes from the vault, so Home shows it on its first frame.
        val app = RuntimeEnvironment.getApplication() as LumioApp
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)

        assertEquals(Watchlist(assets = FIRST_RUN_ASSETS), await { board.watchlist })

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun theBoardShowsTheWatchlistWithNoWalletPaired() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 2).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "unpaired.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)

        assertEquals(Watchlist(assets = FIRST_RUN_ASSETS), await { board.watchlist })
        assertEquals(emptyList<Any>(), board.view!!.wallets)

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun aBitcoinScanOfTheOtherNetworkLeavesTheShownSideReachable() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 11 + 5).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "scan.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)
        val mainnet = NetworkId(Networks.BTC_MAINNET, testnet = false)
        val testnet4 = NetworkId(Networks.BTC_TESTNET4, testnet = true)

        // A mainnet scan fails after the owner switched to testnet: the shown testnet Bitcoin chain did not fail, so it stays reachable.
        runBlocking { app.scanQueue.publish(ScanEvent.Failed(jobId = 1L, net = mainnet, accountKey = "account-a", message = "Could not reach the servers.")) }
        settle()
        assertEquals(emptySet<ChainId>(), board.reads.unreachable)

        // A testnet scan that fails on the shown side makes its chain unreachable.
        runBlocking { app.scanQueue.publish(ScanEvent.Failed(jobId = 2L, net = testnet4, accountKey = "account-b", message = "Could not reach the servers.")) }
        settle()
        assertEquals(setOf(testnet4.chain), board.reads.unreachable)

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun aWatchlistChangeOnAnUnreadableRecordShowsTheNoticeAndKeepsTheRecord() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "unreadable.db"))
        val vault = LumioVault(session, database)
        val damaged = """{"format":3,"watchlist":{"assets":"all"}}"""
        session.putVaultRecord(WatchlistStore.RECORD, damaged.encodeToByteArray())
        val app = RuntimeEnvironment.getApplication() as LumioApp
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)
        await { board.watchlist }

        board.changeWatchlist { it.copy(sort = WatchlistSort.CHANGE_24H) }

        assertEquals("Your saved watchlist could not be read, so nothing was changed.", await { board.watchlistNotice })
        assertEquals(damaged, session.getVaultRecord(WatchlistStore.RECORD)!!.decodeToString())

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun theWatchlistSurvivesPairingPairingAgainAndForgettingAWallet() {
        // A padded vault, as production keeps one: a deleted wallet's slot is the one the next pairing takes, so the id comes back.
        val layout = VaultLayout(tmp.root)
        val keys = MemoryKeystore()
        val key = ByteArray(32) { (it * 3 + 2).toByte() }
        val session = VaultSession(layout, keys, key, pad = WalletPad(layout, keys, key.copyOf(), section = 0))
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "survive.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        vault.wallets = board
        board.showSide(mode, listVersion = 0)
        await { board.watchlist }
        val starred = Watchlist(assets = FIRST_RUN_ASSETS + WatchedAsset(NetworkId(Networks.evm(10143), testnet = true)), sort = WatchlistSort.CHANGE_24H)
        board.changeWatchlist { starred }
        assertEquals(starred, await { board.watchlist?.takeIf { it == starred } })

        // Pairing a wallet leaves the list as it is.
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        board.showSide(mode, listVersion = 1)
        await { board.view?.wallets?.singleOrNull() }
        assertEquals(starred, board.watchlist)

        // So does pairing it again, and so does forgetting it.
        PairedWallets.save(session, pairing(), nowMs = 150L)
        board.showSide(mode, listVersion = 2)
        assertEquals(starred, board.watchlist)
        PairedWallets.delete(session, wallet.id)
        runBlocking { removeWalletHistory(vault, wallet) }
        board.showSide(mode, listVersion = 3)
        await { board.view?.takeIf { it.wallets.isEmpty() } }
        assertEquals(starred, board.watchlist)
        assertEquals(starred, WatchlistStore.read(session))

        // A list read again from the vault is the same, so a board that opens later shows it too.
        val reopened = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        reopened.showSide(mode, listVersion = 0)
        assertEquals(starred, await { reopened.watchlist })

        reopened.close()
        board.close()
        database.close()
        session.close()
    }

    @Test
    fun twoStarsTappedBeforeTheFirstWriteEndsBothStay() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 17 + 3).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "stars.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        vault.wallets = board
        board.showSide(mode, listVersion = 0)
        await { board.watchlist }
        val first = WatchedAsset(NetworkId(Networks.evm(10143), testnet = true))
        val second = WatchedAsset(NetworkId(Networks.evm(1), testnet = true))

        // Both taps come before the main looper runs, so neither has seen the other's write.
        board.changeWatchlist { it.toggled(first) }
        board.changeWatchlist { it.toggled(second) }
        val both = Watchlist(assets = FIRST_RUN_ASSETS + first + second)
        assertEquals(both, await { board.watchlist?.takeIf { it == both } })
        assertEquals(both, WatchlistStore.read(session))

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun aStarOnAWatchlistRecordThatDoesNotParseChangesNothingOnScreen() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 19 + 1).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "refused.db"))
        val vault = LumioVault(session, database)
        val app = RuntimeEnvironment.getApplication() as LumioApp
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val damaged = """{"format":3,"watchlist":{"assets":"all"}}"""
        session.putVaultRecord(WatchlistStore.RECORD, damaged.encodeToByteArray())
        val board = WalletsBoard(app, vault, repository = PriceRepository(app.priceScheduler) { vault.cache }) { emptyMap<AssetId, Price>() }
        vault.wallets = board
        board.showSide(mode, listVersion = 0)
        val shown = await { board.watchlist }

        board.changeWatchlist { it.toggled(WatchedAsset(NetworkId(Networks.evm(10143), testnet = true))) }
        settle()

        assertEquals(shown, board.watchlist)
        assertEquals(damaged, session.getVaultRecord(WatchlistStore.RECORD)?.decodeToString())

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun showingTheCachesAgainStartsNoAccountRead() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 13 + 7).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "caches.db"))
        val vault = LumioVault(session, database)
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        runBlocking { vault.txs.put(listOf(transfer(wallet.evmAddresses.single()))) }
        val board = WalletsBoard(RuntimeEnvironment.getApplication() as LumioApp, vault) { emptyMap<AssetId, Price>() }
        board.showSide(mode, listVersion = 0)
        val account = await { board.view?.accountsOf(wallet.id)?.singleOrNull { it.namespace == Networks.EIP155 } }
        val reads = board.readsOf(account, mode)
        await { reads.activity }

        // An account read ends in the activity, and it reads the holdings of a Solana or an Algorand account from the network first, so no activity
        // after the caches show again means no account read began.
        reads.activity = null
        board.showCaches()
        settle()
        assertNull(reads.activity)

        board.close()
        database.close()
        session.close()
    }

    @Test
    fun showingTheCachesAgainShowsTheTokensTheFirstSyncWroteAfterTheAccountWasRead() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 11 + 5).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "tokens.db"))
        val vault = LumioVault(session, database)
        PairedWallets.save(session, pairing(), nowMs = 100L)
        val wallet = PairedWallets.list(session).single()
        val address = wallet.evmAddresses.single()
        val token = Token(10143L, "0x" + "c2".repeat(20), "TST", "Test Token", 6)
        CustomTokenStore.add(session, token)
        val board = WalletsBoard(RuntimeEnvironment.getApplication() as LumioApp, vault) { emptyMap<AssetId, Price>() }

        // The wallet page opens while the first sync still runs: the account is read with an empty token cache.
        board.showSide(mode, listVersion = 0)
        val account = await { board.view?.accountsOf(wallet.id)?.singleOrNull { it.namespace == Networks.EIP155 } }
        val reads = board.readsOf(account, mode)
        await { reads.tokens }
        assertEquals(emptyList<Any>(), reads.tokens)

        // The first sync writes the token cache, and shows the caches again.
        TokenBalanceCache.write(session, listOf(CachedTokenBalance(wallet.id, 10143L, address, token.contract, "5000000", 1_000L)))
        board.showCaches()
        val shown = await { reads.tokens?.takeIf { it.isNotEmpty() } }

        assertEquals(listOf(token), shown.map { it.token })
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
