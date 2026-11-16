package app.lumio.transactions

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.HdWallet
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.networkId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.Portfolio
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reads of the Transactions tab for one side. The mode filter is the rule under test: a mainnet read never names a testnet chain or account, and a
 * testnet read never names a mainnet one. A disabled chain is never read.
 */
class TxSyncPlanTest {
    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = "https://etherscan.io")
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)
    private val monad = EvmChain(10143L, "Monad testnet", "MON", 18, testnet = true, explorerUrl = null)
    private val chains = listOf(ethereum, sepolia, monad)

    private val wallet = PairedWallet(
        id = "wallet-1",
        name = "Demo",
        fingerprint = 1L,
        createdAtMs = 0L,
        evm = HdWallet(PairFixtures.seed).accountXpub(),
        bitcoin = emptyList(),
    )
    private val walletWithoutEthereum = wallet.copy(evm = null)
    private val btcMainnet = BtcAccountRef("wallet-1", PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.TAPROOT))
    private val btcTestnet = BtcAccountRef("wallet-1", PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT))
    private val settings = EvmSettings(addressCount = 2, enabled = setOf(1L, 11155111L, 10143L))

    private fun plan(mode: NetworkMode, settings: EvmSettings = this.settings, wallets: List<PairedWallet> = listOf(wallet)) =
        TxSync.plan(chains, settings, mode, wallets, listOf(btcMainnet, btcTestnet))

    @Test
    fun mainnetModeReadsOnlyTheEnabledMainnetChainsAndTheMainnetBitcoinAccount() {
        val read = plan(NetworkMode.MAINNET)
        assertTrue(read.evm.all { it.net == NetworkId(Family.EVM, "1", testnet = false) })
        assertEquals(listOf(btcMainnet), read.btc)
    }

    @Test
    fun testnetModeReadsOnlyTheEnabledTestnetChainsAndTheTestnetBitcoinAccount() {
        val read = plan(NetworkMode.TESTNET)
        val nets = read.evm.map { it.net }.toSet()
        assertEquals(setOf(NetworkId(Family.EVM, "11155111", testnet = true), NetworkId(Family.EVM, "10143", testnet = true)), nets)
        assertEquals(listOf(btcTestnet), read.btc)
    }

    @Test
    fun aDisabledChainIsNeverRead() {
        val read = plan(NetworkMode.TESTNET, settings = settings.copy(enabled = setOf(1L)))
        assertTrue(read.evm.isEmpty())
    }

    @Test
    fun eachAddressOfEachWalletIsReadOnEachEnabledChainOfTheSide() {
        val read = plan(NetworkMode.TESTNET)
        val expected = Portfolio.addresses(wallet.evm!!, 2)
        val sepoliaReads = read.evm.filter { it.net.chain == "11155111" }.map { it.address }
        assertEquals(expected, sepoliaReads)
        assertEquals(2 * 2, read.evm.size)
    }

    @Test
    fun aWalletWithoutEthereumAddsNoEvmRead() {
        val read = plan(NetworkMode.MAINNET, wallets = listOf(walletWithoutEthereum))
        assertTrue(read.evm.isEmpty())
        assertEquals(listOf(btcMainnet), read.btc)
    }

    @Test
    fun theNetworksOfTheFeedAreTheSideOnly() {
        assertEquals(
            setOf(NetworkId(Family.EVM, "1", testnet = false), BtcNetwork.MAINNET.networkId()),
            plan(NetworkMode.MAINNET).networks,
        )
        assertEquals(
            setOf(NetworkId(Family.EVM, "11155111", testnet = true), NetworkId(Family.EVM, "10143", testnet = true), BtcNetwork.TESTNET4.networkId()),
            plan(NetworkMode.TESTNET).networks,
        )
    }
}
