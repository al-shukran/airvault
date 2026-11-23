package app.lumio.ui.explore

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lumio.data.net.Networks
import app.lumio.wallets.WalletAccount
import kotlin.test.assertEquals
import org.junit.Test

/** The chain rows of the Explore landing: one row for each chain family the owner's accounts sit on, with its count of accounts. */
class ExploreChainsTest {

    private fun account(chain: ChainId, index: Int) = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(chain, "0x" + "%040x".format(index + 1)),
        index = index,
        networkName = "network",
        coin = null,
        balance = null,
    )

    @Test
    fun eachFamilyWithAccountsHasARowWithItsCountInFamilyOrder() {
        val rows = chainRows(listOf(account(Networks.evm(1L), 0), account(Networks.BTC_MAINNET, 0), account(Networks.evm(8453L), 1)))

        assertEquals(listOf(ChainRow("Bitcoin", accounts = 1), ChainRow("Ethereum", accounts = 2)), rows)
    }

    @Test
    fun noAccountsMeansNoRows() {
        assertEquals(emptyList(), chainRows(emptyList()))
    }
}
