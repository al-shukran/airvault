package app.lumio.watchlist

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.config.UnreadableRecordException
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchlistStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    private val bitcoin = WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false))

    @Test
    fun withNothingStoredTheListIsTheFirstRunAssets() {
        assertEquals(Watchlist(assets = FIRST_RUN_ASSETS), WatchlistStore.read(session))
    }

    @Test
    fun aWatchlistIsStoredAndReadBack() {
        val list = Watchlist(assets = listOf(bitcoin), sort = WatchlistSort.CHANGE_24H)

        WatchlistStore.write(session, list)

        assertEquals(list, WatchlistStore.read(session))
    }

    @Test
    fun aWriteOnARecordThatDoesNotParseIsRefusedAndTheRecordKept() {
        val damaged = """{"format":3,"watchlist":{"assets":"all"}}"""
        session.putVaultRecord(WatchlistStore.RECORD, damaged.encodeToByteArray())

        assertEquals(Watchlist(assets = FIRST_RUN_ASSETS), WatchlistStore.read(session))
        assertThrows(UnreadableRecordException::class.java) { WatchlistStore.write(session, Watchlist()) }

        assertEquals(damaged, session.getVaultRecord(WatchlistStore.RECORD)!!.decodeToString())
    }

    @Test
    fun aRecordOfTheOneListPerWalletFormatIsNotReadAndTheNextWriteReplacesIt() {
        session.putVaultRecord(WatchlistStore.RECORD, """{"format":2,"wallets":{"old":{"assets":[]}}}""".encodeToByteArray())

        assertEquals(Watchlist(assets = FIRST_RUN_ASSETS), WatchlistStore.read(session))
        WatchlistStore.write(session, Watchlist())

        assertEquals(Watchlist(), WatchlistStore.read(session))
    }

    @Test
    fun theLabelKeptWithAStarredAssetIsStoredAndGoesWithItsStar() {
        val ousg = WatchedAsset(NetworkId(Networks.evm(1), testnet = false), "0x1b19c19393e2d034d8ff31ff34c81252fcbbee92")
        val label = WatchedLabel("OUSG", "Ondo Short-Term U.S. Government Bond Fund", "https://coin-images.coingecko.com/coins/images/1/small/ousg.png")
        val starred = Watchlist().toggled(ousg, label)

        WatchlistStore.write(session, starred)

        assertEquals(label, WatchlistStore.read(session).labels[ousg.key])
        assertEquals(emptyMap<String, WatchedLabel>(), starred.toggled(ousg).labels)
    }
}
