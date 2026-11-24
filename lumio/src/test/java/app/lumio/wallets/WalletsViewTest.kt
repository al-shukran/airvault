package app.lumio.wallets

import app.airvault.core.model.AccountId
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.home.NativeAsset
import app.lumio.pair.PairedWallet
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The wallet totals of the Accounts tab and the wallet page: the value and the 24-hour change of a wallet, and the order of the wallet rows. */
class WalletsViewTest {

    private val ethereum = NetworkId(Networks.evm(1L), testnet = false)
    private val base = NetworkId(Networks.evm(8453L), testnet = false)

    private fun wallet(id: String) = PairedWallet(id = id, name = id, fingerprint = 1L, createdAtMs = 1L, bitcoin = emptyList())

    private fun account(walletId: String, index: Int, network: NetworkId, amount: String) = WalletAccount(
        walletId = walletId,
        id = AccountId.of(network.chain, "0x" + "%040x".format(index + 1)),
        index = index,
        networkName = "network",
        coin = null,
        balance = NativeAsset("native-$index", "ETH", "Ethereum", network, BigDecimal(amount)),
    )

    @Test
    fun aWalletsTotalAndChangeAreThoseOfItsPricedBalances() {
        // Two ETH at $1,000 that rose 10% over 24 hours. The balance on Base has no price, so it adds nothing.
        val view = WalletsView(
            wallets = listOf(wallet("a")),
            accounts = mapOf("a" to listOf(account("a", 0, ethereum, "2"), account("a", 1, base, "5"))),
            prices = mapOf(AssetId.Native(ethereum) to Price(usd = 1_000.0, change24hPct = 10.0, asOfMs = 1L, stale = false)),
        )

        val total = view.total("a")

        assertEquals(2_000.0, total.usd!!, 1e-9)
        assertEquals(10.0, total.changePct!!, 1e-9)
        assertEquals(2_000.0 - 2_000.0 / 1.1, total.changeUsd!!, 1e-9)
    }

    @Test
    fun aWalletWithNoPricedBalanceHasNoTotalOrChange() {
        val view = WalletsView(
            wallets = listOf(wallet("a")),
            accounts = mapOf("a" to listOf(account("a", 0, base, "5"))),
            prices = emptyMap(),
        )

        val total = view.total("a")

        assertNull(total.usd)
        assertNull(total.changeUsd)
        assertNull(total.changePct)
    }

    @Test
    fun theActiveWalletComesFirstAndTheRestKeepTheVaultsOrder() {
        val view = WalletsView(wallets = listOf(wallet("a"), wallet("b"), wallet("c")), accounts = emptyMap(), prices = emptyMap())

        assertEquals(listOf("b", "a", "c"), view.walletsActiveFirst("b").map { it.id })
        assertEquals(listOf("a", "b", "c"), view.walletsActiveFirst("a").map { it.id })
        assertEquals(listOf("a", "b", "c"), view.walletsActiveFirst(null).map { it.id })
        assertEquals(listOf("a", "b", "c"), view.walletsActiveFirst("gone").map { it.id })
    }
}
