package app.lumio.home

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.wallet.HdWallet
import app.lumio.btc.BtcAccountRef
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rows of the Send and Receive pickers and of an asset's accounts, from the paired wallets. */
class HomeAccountsTest {
    private val withEthereum = PairedWallet(
        id = "w1",
        name = "Demo",
        fingerprint = 1L,
        createdAtMs = 1L,
        evm = HdWallet(PairFixtures.seed).accountXpub(),
        bitcoin = emptyList(),
    )
    private val bitcoinOnly = PairedWallet(
        id = "w2",
        name = "Bitcoin only",
        fingerprint = 2L,
        createdAtMs = 2L,
        evm = null,
        bitcoin = BtcWatchOnly.accounts(PairFixtures.seed, BtcNetwork.MAINNET),
    )
    private val mainnetRefs = bitcoinOnly.bitcoin.map { BtcAccountRef(bitcoinOnly.id, it) }

    @Test
    fun aSendOffersEachEthereumWalletAndTheBitcoinSendWhenTheSideHasBitcoin() {
        val options = HomeAccounts.sendFrom(NetworkMode.MAINNET, listOf(withEthereum, bitcoinOnly), mainnetRefs)

        assertEquals(listOf(PickTarget.Evm("w1"), PickTarget.Bitcoin), options.map { it.target })
        assertEquals("${mainnetRefs.size} accounts on Mainnet", options[1].detail)
    }

    @Test
    fun aSendHasNoBitcoinRowWhenTheSideHasNoBitcoinAccount() {
        val options = HomeAccounts.sendFrom(NetworkMode.TESTNET, listOf(withEthereum), emptyList())

        assertEquals(listOf(PickTarget.Evm("w1")), options.map { it.target })
    }

    @Test
    fun aReceiveOffersEachEthereumAddressAndEachBitcoinAccountOfTheSide() {
        val options = HomeAccounts.receiveFor(listOf(withEthereum, bitcoinOnly), mainnetRefs)

        assertEquals(
            listOf(PickTarget.Evm("w1")) + mainnetRefs.map { PickTarget.BitcoinAccount(it.key) },
            options.map { it.target },
        )
        assertEquals("Demo, address 1", options.first().detail)
        assertEquals("Bitcoin ${HomeAccounts.scriptLabel(mainnetRefs.last().account.scriptType)}", options.last().title)
    }

    @Test
    fun anEthereumCoinsPageListsEveryWalletWithAnEthereumAccount() {
        val asset = NativeAsset(
            id = "EVM-1",
            symbol = "ETH",
            name = "Ethereum",
            network = NetworkId(Family.EVM, "1", testnet = false),
            balance = BigDecimal.ONE,
        )

        val accounts = HomeAccounts.holding(asset, listOf(withEthereum, bitcoinOnly))

        assertEquals(listOf(PickTarget.Evm("w1")), accounts.map { it.target })
    }

    @Test
    fun aBitcoinCoinsPageListsTheWalletsWithAnAccountOnItsNetwork() {
        val asset = NativeAsset(
            id = "BTC-mainnet",
            symbol = "BTC",
            name = "Bitcoin",
            network = NetworkId(Family.BTC, "mainnet", testnet = false),
            balance = BigDecimal.ONE,
        )

        val accounts = HomeAccounts.holding(asset, listOf(withEthereum, bitcoinOnly))

        assertEquals(listOf(PickTarget.BitcoinWallet("w2")), accounts.map { it.target })
    }
}
