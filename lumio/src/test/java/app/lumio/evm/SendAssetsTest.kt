package app.lumio.evm

import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Test

/** The asset picker's list: the native coin, then the tokens the sending address holds on the chain, then a preferred token that is not held. */
class SendAssetsTest {
    private val mainnet = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)
    private val usdc = Token(1L, USDC, "USDC", "USD Coin", 6)
    private val dai = Token(1L, DAI, "DAI", "Dai Stablecoin", 18)
    private val usdcSepolia = Token(11155111L, USDC, "USDC", "USD Coin", 6)

    private fun holding(chain: EvmChain, token: Token, amount: Long) = TokenHolding(chain, token, BigInteger.valueOf(amount), atMs = 1L)

    @Test
    fun theNativeCoinIsFirstThenEachTokenHeldOnTheChain() {
        val held = listOf(holding(mainnet, usdc, 10_000_000L), holding(mainnet, dai, 5L))

        val assets = SendAssets.onChain(mainnet, listOf(usdc, dai), held, preferred = null)

        assertEquals(listOf<String?>(null, "USDC", "DAI"), assets.map { it.token?.symbol })
        assertEquals("ETH", assets[0].symbol)
        assertEquals(BigInteger.valueOf(10_000_000), assets[1].balance)
        assertEquals(6, assets[1].decimals)
    }

    @Test
    fun aTokenWithNoBalanceIsNotListedUnlessItIsPreferred() {
        val held = listOf(holding(mainnet, usdc, 0L))

        assertEquals(listOf<String?>(null), SendAssets.onChain(mainnet, listOf(usdc), held, preferred = null).map { it.token?.symbol })
    }

    @Test
    fun aPreferredTokenIsListedWithNoBalanceWhenItIsNotHeld() {
        val assets = SendAssets.onChain(mainnet, listOf(usdc, dai), held = emptyList(), preferred = DAI)

        assertEquals(listOf<String?>(null, "DAI"), assets.map { it.token?.symbol })
        assertEquals(null, assets[1].balance)
    }

    @Test
    fun aPreferredTokenThatIsHeldIsListedOnce() {
        val held = listOf(holding(mainnet, usdc, 1L))

        val assets = SendAssets.onChain(mainnet, listOf(usdc), held, preferred = USDC)

        assertEquals(listOf<String?>(null, "USDC"), assets.map { it.token?.symbol })
    }

    @Test
    fun aPreferredTokenThatTheChainDoesNotListIsNotListed() {
        val assets = SendAssets.onChain(mainnet, listOf(usdc), held = emptyList(), preferred = "0x" + "ab".repeat(20))

        assertEquals(listOf<String?>(null), assets.map { it.token?.symbol })
    }

    @Test
    fun holdingsOfAnotherChainAreNotListed() {
        val held = listOf(holding(sepolia, usdcSepolia, 9L))

        assertEquals(listOf<String?>(null), SendAssets.onChain(mainnet, listOf(usdc), held, preferred = null).map { it.token?.symbol })
        assertEquals(listOf<String?>(null, "USDC"), SendAssets.onChain(sepolia, listOf(usdcSepolia), held, preferred = null).map { it.token?.symbol })
    }

    private companion object {
        const val USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val DAI = "0x6b175474e89094c44da98b954eedeac495271d0f"
    }
}
