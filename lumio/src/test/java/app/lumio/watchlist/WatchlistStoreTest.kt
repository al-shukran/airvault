package app.lumio.watchlist

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
