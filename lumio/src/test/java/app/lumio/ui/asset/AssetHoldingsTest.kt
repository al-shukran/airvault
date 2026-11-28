package app.lumio.ui.asset

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.evm.EvmChain
import app.lumio.home.NativeAsset
import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import app.lumio.wallets.AccountCoin
import app.lumio.wallets.WalletAccount
import app.lumio.watchlist.WatchedAsset
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Holdings Breakdown's grouping and the asset page's text: a coin by chain, the largest account first, the amounts and addresses as written, and the
 * accounts that can hold a coin.
 */
class AssetHoldingsTest {
    private val ethereum = Networks.evm(1L)
    private val arbitrum = Networks.evm(42161L)
    private val ethereumChain = EvmChain(chainId = 1L, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)
    private val arbitrumChain = EvmChain(chainId = 42161L, name = "Arbitrum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)
    private val nativeEth = WatchedAsset(NetworkId(ethereum, testnet = false))
    private val usdc = WatchedAsset(NetworkId(ethereum, testnet = false), contract = "0xa0b8")

    private fun account(chain: ChainId, network: String, name: String?, held: NativeAsset?, index: Int = 0): WalletAccount = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(chain, "0x102030405060708090a0b0c0d0e0f0101122b"),
        index = index,
        networkName = network,
        coin = null,
        balance = held,
        name = name,
    )

    private fun eth(chain: ChainId, amount: String, symbol: String = "ETH"): NativeAsset = NativeAsset(
        id = "$symbol-$amount",
        symbol = symbol,
        name = symbol,
        network = NetworkId(chain, testnet = false),
        balance = BigDecimal(amount),
    )

    @Test
    fun chainsAreInNameOrderAndEachChainsAccountsLargestFirst() {
        val accounts = listOf(
            account(ethereum, "Ethereum", "Unnamed", eth(ethereum, "1")),
            account(arbitrum, "Arbitrum", "FelixStone", eth(arbitrum, "3.23")),
            account(arbitrum, "Arbitrum", "PulsarNova", eth(arbitrum, "10"), index = 5),
        )

        val chains = chainHoldings(holdingsOf(nativeEth, "ETH", accounts, emptyMap()), walletName = "Air 1")

        assertEquals(listOf("Arbitrum", "Ethereum"), chains.map { it.chain })
        assertEquals(listOf(BigDecimal("13.23"), BigDecimal("1")), chains.map { it.total })
        assertEquals(listOf("PulsarNova", "FelixStone"), chains.first().accounts.map { it.name })
        assertEquals("Air 1 #5", chains.first().accounts.first().detail)
        assertEquals(accounts[2].address, chains.first().accounts.first().address, "the address is a line of its own, not part of the detail")
    }

    @Test
    fun eachChainCarriesTheNetworkThatNamesItsLogoInTheSameOrder() {
        val accounts = listOf(
            account(ethereum, "Ethereum", "Unnamed", eth(ethereum, "1")),
            account(arbitrum, "Arbitrum", "FelixStone", eth(arbitrum, "3.23")),
        )

        val chains = chainHoldings(holdingsOf(nativeEth, "ETH", accounts, emptyMap()), walletName = "Air 1")

        assertEquals(listOf("Arbitrum", "Ethereum"), chains.map { it.chain })
        assertEquals(listOf(NetworkId(arbitrum, testnet = false), NetworkId(ethereum, testnet = false)), chains.map { it.network })
    }

    @Test
    fun otherCoinsAndEmptyBalancesAreLeftOut() {
        val accounts = listOf(
            account(ethereum, "Ethereum", "Holds", eth(ethereum, "2")),
            account(ethereum, "Ethereum", "Empty", eth(ethereum, "0")),
            account(ethereum, "Ethereum", "Usdc", eth(ethereum, "500", symbol = "USDC")),
            account(ethereum, "Ethereum", "NoRead", held = null),
        )

        val chains = chainHoldings(holdingsOf(nativeEth, "ETH", accounts, emptyMap()), walletName = "Air 1")

        assertEquals(listOf("Holds"), chains.single().accounts.map { it.name })
    }

    @Test
    fun aTokenIsReadFromItsOwnChainScaledByItsDecimals() {
        val token = Token(chainId = 1L, contract = "0xa0b8", symbol = "USDC", name = "USD Coin", decimals = 6)
        val holds = account(ethereum, "Ethereum", "Holds", held = null)
        val otherChain = Token(chainId = 42161L, contract = "0xa0b8", symbol = "USDC", name = "USD Coin", decimals = 6)
        val tokens = mapOf(
            holds to listOf(
                TokenHolding(ethereumChain, token, BigInteger("1500000"), atMs = 0L),
                TokenHolding(arbitrumChain, otherChain, BigInteger("2000000"), atMs = 0L),
                TokenHolding(ethereumChain, token.copy(contract = "0xdead"), BigInteger("9"), atMs = 0L),
            ),
        )

        val held = holdingsOf(usdc, "USDC", listOf(holds), tokens)

        assertEquals(listOf("Ethereum"), held.map { it.chain })
        assertEquals(0, BigDecimal("1.5").compareTo(held.single().amount))
    }

    @Test
    fun aNativeCoinIsHeldOnItsNetworkAndATokenByAnyEthereumAccount() {
        val onEthereum = account(ethereum, "Ethereum", "A", held = null).copy(coin = AccountCoin("ETH", "Ethereum", NetworkId(ethereum, testnet = false)))
        val onArbitrum = account(arbitrum, "Arbitrum", "B", held = null).copy(coin = AccountCoin("ETH", "Arbitrum", NetworkId(arbitrum, testnet = false)))

        assertTrue(canHold(onEthereum, nativeEth))
        assertFalse(canHold(onArbitrum, nativeEth))
        assertTrue(canHold(onArbitrum, WatchedAsset(NetworkId(arbitrum, testnet = false), contract = "0xa0b8")))
    }

    @Test
    fun aTokenIsHeldOnlyByAnAccountOfItsOwnFamily() {
        val onEthereum = account(ethereum, "Ethereum", "A", held = null)
        val onSolana = account(Networks.SOL_MAINNET, "Solana", "S", held = null)
        val onAlgorand = account(Networks.ALGO_MAINNET, "Algorand", "G", held = null)
        val spl = WatchedAsset(NetworkId(Networks.SOL_MAINNET, testnet = false), contract = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        val asa = WatchedAsset(NetworkId(Networks.ALGO_MAINNET, testnet = false), contract = "31566704")

        // An Ethereum account never offers Send or Receive of an SPL token or an Algorand asset, whose send route has no EVM chain.
        assertFalse(canHold(onEthereum, spl))
        assertFalse(canHold(onEthereum, asa))
        assertTrue(canHold(onSolana, spl))
        assertTrue(canHold(onAlgorand, asa))
        assertFalse(canHold(onSolana, WatchedAsset(NetworkId(ethereum, testnet = false), contract = "0xa0b8")))
    }

    @Test
    fun theSummaryCountsAccountsAndChainsInTheirSingularOrPlural() {
        assertEquals("5 accounts · 3 chains", holdingsSummary(accounts = 5, chains = 3))
        assertEquals("1 account · 1 chain", holdingsSummary(accounts = 1, chains = 1))
    }

    @Test
    fun anAmountDropsItsTrailingZerosAndKeepsItsTicker() {
        assertEquals("4 ETH", amountText(BigDecimal("4.000"), "ETH"))
        assertEquals("22.23 ETH", amountText(BigDecimal("22.23"), "ETH"))
    }
}
