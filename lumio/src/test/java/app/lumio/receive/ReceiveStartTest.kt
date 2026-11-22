package app.lumio.receive

import app.airvault.core.model.AccountId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.receive.receiveNativeStartOf
import app.lumio.wallets.AccountCoin
import app.lumio.wallets.WalletAccount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A Receive opened from an account's native coin needs only the account's address, so it opens at its sheet with no balance read. */
class ReceiveStartTest {

    private val network = NetworkId(Networks.evm(10143), testnet = true)
    private val coin = AccountCoin(symbol = "MON", name = "Monad testnet", network = network)
    private val account = WalletAccount(
        walletId = "wallet-1",
        id = AccountId.of(Networks.evm(10143), ADDRESS),
        index = 0,
        networkName = "Monad testnet",
        coin = coin,
        balance = null,
    )

    @Test
    fun opensAtTheReceiveSheetWhileTheBalanceIsUnread() {
        val answers = receiveNativeStartOf(account, coin, AssetIconSpec.Letter("M"), wallets = emptyList())

        val step = receiveStep(answers, assets = emptyList())
        assertIs<ReceiveStep.Receive>(step)
        assertEquals("MON", step.coin.symbol)
        assertEquals("Monad testnet", step.network.name)
        assertEquals(ADDRESS, step.account.address)
    }

    private companion object {
        const val ADDRESS = "0x102030405060708090112131415161718191122b"
    }
}
