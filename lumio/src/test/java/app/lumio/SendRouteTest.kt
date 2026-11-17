package app.lumio

import app.lumio.network.NetworkMode
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Send route of each chain's account: Bitcoin opens its form, Ethereum, Solana and Algorand open their send from the account. */
class SendRouteTest {
    private fun account(chain: AccountChain) = WalletAccount(
        walletId = "w1",
        chain = chain,
        index = 2,
        address = "address",
        networkName = "network",
        balance = null,
    )

    @Test
    fun algorandAccountOpensTheAlgorandSend() {
        assertEquals("algorand-send/w1/2", sendRoute(account(AccountChain.ALGORAND), NetworkMode.MAINNET))
        assertEquals("algorand-send/w1/2", sendRoute(account(AccountChain.ALGORAND), NetworkMode.TESTNET))
    }

    @Test
    fun solanaAccountOpensTheSolanaSend() {
        assertEquals("solana-send/w1/2", sendRoute(account(AccountChain.SOLANA), NetworkMode.MAINNET))
    }
}
