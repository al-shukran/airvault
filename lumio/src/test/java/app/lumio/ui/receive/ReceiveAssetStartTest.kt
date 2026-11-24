package app.lumio.ui.receive

import app.airvault.core.model.AccountId
import app.lumio.data.net.Networks
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.wallets.WalletAccount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Receive opened from the asset page of an Algorand asset carries the asset's ID into the Receive, so the Receive can ask whether the account has opted
 * in to it. A native coin, or a row of another chain, carries none.
 */
class ReceiveAssetStartTest {
    private val algorand = WalletAccount(
        walletId = "wallet-1",
        id = AccountId.of(Networks.ALGO_TESTNET, ADDRESS),
        index = 0,
        networkName = "Algorand Testnet",
        coin = null,
        balance = null,
    )

    @Test
    fun anAlgorandAssetPageReceiveCarriesTheAssetIntoTheReceive() {
        val start = receiveStartOf(row(key = "10458941", symbol = "USDC"), algorand, wallets = emptyList())

        assertEquals(10458941UL, start.coin?.algorandAssetId)
    }

    @Test
    fun aNativeAlgoReceiveCarriesNoAsset() {
        val start = receiveStartOf(row(key = "native", symbol = "ALGO"), algorand, wallets = emptyList())

        assertNull(start.coin?.algorandAssetId)
    }

    private fun row(key: String, symbol: String) = AssetRowModel(
        assetKey = key,
        symbol = symbol,
        name = symbol,
        network = "Algorand Testnet",
        amountText = "1",
        usd = null,
        icon = AssetIconSpec.Letter(symbol.take(1)),
        assetId = null,
        isNative = key == "native",
        testnet = true,
        zero = false,
    )

    private companion object {
        val ADDRESS = "A".repeat(58)
    }
}
