package app.lumio

import app.lumio.network.NetworkMode
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Send route of each chain's account. Every chain sends from its own account, bound to the network the account shows, with no picker: Ethereum
 * on the side's primary network, Bitcoin from that account, Solana and Algorand from that account.
 */
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
    fun ethereumAccountOpensItsSendOnThePrimaryNetworkOfTheSide() {
        assertEquals("send/w1/2?chain=1", sendRoute(account(AccountChain.ETHEREUM), NetworkMode.MAINNET))
        assertEquals("send/w1/2?chain=10143", sendRoute(account(AccountChain.ETHEREUM), NetworkMode.TESTNET))
    }

    @Test
    fun bitcoinAccountOpensItsSendFromThatAccount() {
        assertEquals("bitcoin-send/w1/2", sendRoute(account(AccountChain.BITCOIN), NetworkMode.MAINNET))
        assertEquals("bitcoin-send/w1/2", sendRoute(account(AccountChain.BITCOIN), NetworkMode.TESTNET))
    }

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
