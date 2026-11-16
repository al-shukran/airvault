package app.lumio.home

import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import java.math.BigDecimal
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Home's spam rule: with Hide spam on, an unknown token that has no price is not listed. A priced unknown token and every known coin stay, and with
 * Hide spam off, nothing is left out.
 */
class HomeSpamFilterTest {
    private val eth = NetworkId(Family.EVM, "1", testnet = false)

    private val native = NativeAsset(
        id = NativeAsset.idOf(eth),
        symbol = "ETH",
        name = "Ethereum",
        network = eth,
        balance = BigDecimal.ONE,
    )

    private fun token(symbol: String, usd: Double?, known: Boolean) = TokenAsset(
        id = TokenAsset.idOf(eth, "0x$symbol"),
        symbol = symbol,
        name = "Ethereum",
        network = eth,
        contract = "0x$symbol",
        balance = BigDecimal.ONE,
        usd = usd,
        known = known,
    )

    private val assets = listOf(
        native,
        token("KNOWN", usd = null, known = true),
        token("PRICED", usd = 5.0, known = false),
        token("AIRDROP", usd = null, known = false),
    )

    @Test
    fun withHideSpamOnAnUnknownUnpricedTokenIsNotListed() {
        val kept = HomeAssets.withoutSpam(assets, hideSpam = true).map { it.symbol }
        assertEquals(listOf("ETH", "KNOWN", "PRICED"), kept)
    }

    @Test
    fun withHideSpamOffEveryAssetIsListed() {
        assertEquals(assets, HomeAssets.withoutSpam(assets, hideSpam = false))
    }

    @Test
    fun aTokenIsKnownUnlessItIsMarkedUnknown() {
        assertEquals(true, token("ANY", usd = null, known = true).known)
        assertEquals(true, TokenAsset(id = "x", symbol = "X", name = "n", network = eth, contract = "0x1", balance = BigDecimal.ONE).known)
    }
}
