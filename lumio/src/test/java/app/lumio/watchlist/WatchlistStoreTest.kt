package app.lumio.watchlist

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.config.UnreadableRecordException
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun removingAWalletRemovesItsWatchlistAndKeepsTheOthers() {
        val bitcoin = WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false))
        val ethereum = WatchedAsset(NetworkId(Networks.evm(1), testnet = false))
        val other = WalletWatchlist(assets = listOf(bitcoin), sort = WatchlistSort.CHANGE_24H)
        WatchlistStore.write(session, "removed", WalletWatchlist(assets = listOf(ethereum)))
        WatchlistStore.write(session, "kept", other)

        WatchlistStore.remove(session, "removed")

        assertNull(WatchlistStore.read(session, "removed"))
        assertEquals(other, WatchlistStore.read(session, "kept"))
    }

    @Test
    fun aWriteOrRemoveOnARecordThatDoesNotParseIsRefusedAndTheRecordKept() {
        val damaged = """{"wallets":{"kept":{"assets":"all"}}}"""
        session.putVaultRecord(WatchlistStore.RECORD, damaged.encodeToByteArray())

        assertEquals(emptyMap<String, WalletWatchlist>(), WatchlistStore.readAll(session))
        assertNull(WatchlistStore.read(session, "kept"))
        assertThrows(UnreadableRecordException::class.java) { WatchlistStore.write(session, "new", WalletWatchlist()) }
        assertThrows(UnreadableRecordException::class.java) { WatchlistStore.remove(session, "kept") }

        assertEquals(damaged, session.getVaultRecord(WatchlistStore.RECORD)!!.decodeToString())
    }

    @Test
    fun aWriteWithNoRecordSucceeds() {
        val list = WalletWatchlist(assets = listOf(WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false))))

        WatchlistStore.write(session, "first", list)

        assertEquals(mapOf("first" to list), WatchlistStore.readAll(session))
    }
}
