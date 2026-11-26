package app.lumio

import app.lensly.core.registry.TokenEntry
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.airvault.core.model.AccountId
import app.lumio.evm.EvmChain
import app.lumio.network.NetworkMode
import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import app.lumio.wallets.WalletAccount
import app.lumio.watchlist.WatchedAsset
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

/** How Home names a watched token. */
class HomeTokenRowTest {
    private val contract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val ethereum = NetworkId(Networks.evm(1), testnet = false)
    private val usdc = WatchedAsset(ethereum, contract = contract)
    private val chain = EvmChain(chainId = 1L, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)
    private fun held(symbol: String, name: String, amount: Long) =
        TokenHolding(chain, Token(1L, contract, symbol, name, decimals = 6), BigInteger.valueOf(amount), atMs = 0L)

    @Test
    fun aListedTokenIsNamedByTheRegistry() {
        val listed = TokenEntry(chainId = 1L, contract = contract, symbol = "USDC", name = "USD Coin", decimals = 6)
        assertEquals("USDC" to "USD Coin", namesOf(usdc, listed, emptyList()))
    }

    @Test
    fun anUnlistedTokenTheWalletHoldsIsNamedByItsHolding() {
        val tokens = listOf(listOf(held("OWN", "Own Token", 5)))
        assertEquals("OWN" to "Own Token", namesOf(usdc, null, tokens))
    }

    @Test
    fun anUnknownTokenKeepsItsGroupedAddress() {
        val grouped = coinNames(usdc)
        assertEquals(grouped.first, namesOf(usdc, null, emptyList()).first)
        assertEquals(true, grouped.first.startsWith("0x"))
    }

    @Test
    fun aTokenIsNotNamedByTheSameAddressHeldOnAnotherChain() {
        val arbitrum = EvmChain(chainId = 42161L, name = "Arbitrum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)
        val other = TokenHolding(arbitrum, Token(42161L, contract, "OTHER", "Other Token", decimals = 6), BigInteger.TEN, atMs = 0L)

        assertEquals(coinNames(usdc), namesOf(usdc, null, listOf(listOf(other))))
        assertEquals("OWN" to "Own Token", namesOf(usdc, null, listOf(listOf(other), listOf(held("OWN", "Own Token", 5)))))
    }

    @Test
    fun aTokenIsNotNamedByAHoldingOnTheOtherSideOfTheTestnetFlag() {
        val sepolia = EvmChain(chainId = 1L, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = true, explorerUrl = null)
        val testnetHolding = TokenHolding(sepolia, Token(1L, contract, "TEST", "Test Token", decimals = 6), BigInteger.TEN, atMs = 0L)

        assertEquals(coinNames(usdc), namesOf(usdc, null, listOf(listOf(testnetHolding))))
    }

    private fun account(chain: app.airvault.core.model.ChainId) = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(chain, "0x102030405060708090a0b0c0d0e0f0101122b"),
        index = 0,
        networkName = "Network",
        coin = null,
        balance = null,
    )

    @Test
    fun aSolanaOrAlgorandTokenHasNoSendRouteAndNeverOpensTheNativeSend() {
        val spl = WatchedAsset(NetworkId(Networks.SOL_MAINNET, testnet = false), contract = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        val asa = WatchedAsset(NetworkId(Networks.ALGO_MAINNET, testnet = false), contract = "31566704")

        assertNull(coinSendRoute(account(Networks.SOL_MAINNET), spl, NetworkMode.MAINNET))
        assertNull(coinSendRoute(account(Networks.ALGO_MAINNET), asa, NetworkMode.MAINNET))
    }

    @Test
    fun aNativeCoinAndAnEvmTokenKeepTheirSendRoutes() {
        val sol = WatchedAsset(NetworkId(Networks.SOL_MAINNET, testnet = false))

        assertNotNull(coinSendRoute(account(Networks.SOL_MAINNET), sol, NetworkMode.MAINNET))
        assertEquals(true, coinSendRoute(account(Networks.evm(1L)), usdc, NetworkMode.MAINNET)?.contains(contract))
    }
}
