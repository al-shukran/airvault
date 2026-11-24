package app.lumio

import app.airvault.core.model.AccountId
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.receive.ReceiveCapsule
import app.lumio.receive.receiveCapsuleOf
import app.lumio.ui.receive.receiveStartOf
import app.lumio.ui.wallets.AssetRows
import app.lumio.wallets.WalletAccount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The path to Opt In First for an Algorand asset that the account has no balance of: Search finds the asset and opens its page on the account, the page
 * has a row for it, and Receive on that row offers the opt-in until the account has opted in.
 */
class UnoptedAsaSearchPathTest {
    private val account = WalletAccount(
        walletId = "wallet-1",
        id = AccountId.of(Networks.ALGO_TESTNET, ADDRESS),
        index = 0,
        networkName = "Algorand Testnet",
        coin = null,
        balance = null,
    )

    @Test
    fun aVerifiedAssetFoundBySearchOpensItsPageAndReceiveOffersOptInFirst() {
        assertPathOffersOptInFirst(query = "10458941", assetId = 10458941UL)
    }

    @Test
    fun aTypedAssetIdFoundBySearchOpensItsPageAndReceiveOffersOptInFirst() {
        assertPathOffersOptInFirst(query = "99999999", assetId = 99999999UL)
    }

    private fun assertPathOffersOptInFirst(query: String, assetId: ULong) {
        val opened = mutableListOf<String>()
        val rows = algorandAssetRows(query, account, NetworkMode.TESTNET) { opened += it }
        rows.single().onOpen?.invoke()
        assertEquals(listOf(LumioRoute.asset("wallet-1", account.id, assetId.toString())), opened)

        val row = assertNotNull(AssetRows.unheldAlgorandRow(account, assetId.toString()))
        val coin = assertNotNull(receiveStartOf(row, account, wallets = emptyList()).coin)
        assertEquals(ReceiveCapsule.OptInFirst(assetId), receiveCapsuleOf(coin, optedIn = false))
        assertEquals(ReceiveCapsule.CopyAddress, receiveCapsuleOf(coin, optedIn = true))
    }

    private companion object {
        val ADDRESS = "A".repeat(58)
    }
}
