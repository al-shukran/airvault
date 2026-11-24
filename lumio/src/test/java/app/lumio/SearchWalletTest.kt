package app.lumio

import app.lumio.pair.PairedWallet
import kotlin.test.assertEquals
import org.junit.Test

/**
 * The search reads the wallet that the profile panel chose, as Home does, and the first paired wallet until the owner chooses one. The watchlist and the
 * pasted-address row both follow it.
 */
class SearchWalletTest {
    private val wallets = listOf(wallet("first"), wallet("second"))
    private val address = "0x" + "1".repeat(40)

    @Test
    fun theWatchlistFollowsTheWalletThePanelChose() {
        val selection = NetworkSelection().apply { walletId = "second" }

        assertEquals("second", wallets.active(selection) { it.id }?.id)
    }

    @Test
    fun theFirstWalletIsTheWatchlistUntilOneIsChosen() {
        assertEquals("first", wallets.active(NetworkSelection()) { it.id }?.id)
    }

    @Test
    fun thePastedAddressOpensTheWalletThePanelChoseNotTheFirstOne() {
        val selection = NetworkSelection().apply { walletId = "second" }
        val opened = mutableListOf<String>()

        val row = addressRows(address, wallets.active(selection) { it.id }) { route -> opened += route }.single()
        row.onOpen?.invoke()

        assertEquals(listOf(LumioRoute.wallet("second")), opened)
    }

    private fun wallet(id: String) = PairedWallet(id = id, name = id, fingerprint = 0L, createdAtMs = 0L, bitcoin = emptyList())
}
