package app.lumio

import app.lensly.core.registry.TokenEntry
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.evm.EvmChain
import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import app.lumio.watchlist.WatchedAsset
import java.math.BigInteger
import kotlin.test.assertEquals
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
}
