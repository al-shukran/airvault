package app.lumio

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lumio.chains.ChainHolding
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.ui.wallets.AssetRows
import app.lumio.wallets.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Send route of each chain's account. Every chain sends from its own account, bound to the network the account shows, with no picker: Ethereum
 * on the side's primary network, Bitcoin from that account, Solana and Algorand from that account. The route names the account by its CAIP-10 id.
 */
class SendRouteTest {
    private fun account(chain: ChainId) = WalletAccount(
        walletId = "w1",
        id = AccountId.of(chain, "address"),
        index = 2,
        networkName = "network",
        coin = null,
        balance = null,
    )

    @Test
    fun ethereumAccountOpensItsSendOnThePrimaryNetworkOfTheSide() {
        assertEquals("send/w1/eip155:1:address?chain=1", sendRoute(account(Networks.evm(1L)), NetworkMode.MAINNET))
        assertEquals("send/w1/eip155:10143:address?chain=10143", sendRoute(account(Networks.evm(10143L)), NetworkMode.TESTNET))
    }

    @Test
    fun bitcoinAccountOpensItsSendFromThatAccount() {
        assertEquals("bitcoin-send/w1/bip122:000000000019d6689c085ae165831e93:address", sendRoute(account(Networks.BTC_MAINNET), NetworkMode.MAINNET))
        assertEquals("bitcoin-send/w1/bip122:00000000da84f2bafbbc53dee25a72ae:address", sendRoute(account(Networks.BTC_TESTNET4), NetworkMode.TESTNET))
    }

    @Test
    fun algorandAccountOpensTheAlgorandSend() {
        assertEquals("algorand-send/w1/algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:address", sendRoute(account(Networks.ALGO_MAINNET), NetworkMode.MAINNET))
        assertEquals("algorand-send/w1/algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDe:address", sendRoute(account(Networks.ALGO_TESTNET), NetworkMode.TESTNET))
    }

    @Test
    fun solanaAccountOpensTheSolanaSend() {
        assertEquals("solana-send/w1/solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp:address", sendRoute(account(Networks.SOL_MAINNET), NetworkMode.MAINNET))
    }

    @Test
    fun theSendOfAHeldSplTokenOpensTheSolanaSendOnThatMint() {
        val solana = account(Networks.SOL_MAINNET)
        val holding = ChainHolding("USDC", "Solana", BigInteger("1500000"), 6, AssetId.Spl(NetworkId(Networks.SOL_MAINNET, testnet = false), MINT))

        assertEquals("solana-send/w1/solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp:address?coin=$MINT", assetSendRoute(solana, rowOf(solana, holding), NetworkMode.MAINNET))
    }

    @Test
    fun theSendOfAHeldAlgorandAssetOpensTheAlgorandSendOnThatAsset() {
        val algorand = account(Networks.ALGO_MAINNET)
        val holding = ChainHolding("USDC", "Algorand", BigInteger("2500000"), 6, AssetId.Asa(NetworkId(Networks.ALGO_MAINNET, testnet = false), 31566704UL))

        assertEquals(
            "algorand-send/w1/algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:address?coin=31566704",
            assetSendRoute(algorand, rowOf(algorand, holding), NetworkMode.MAINNET),
        )
    }

    private fun rowOf(account: WalletAccount, holding: ChainHolding) =
        AssetRows.of(account, emptyList(), listOf(holding), emptyMap(), network = { null }, token = { _, _ -> null }).single()

    private companion object {
        val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    }
}
