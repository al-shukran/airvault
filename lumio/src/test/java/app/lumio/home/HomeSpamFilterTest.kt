package app.lumio.home

import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import java.math.BigDecimal
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Home's spam rule: with Hide spam on, a mainnet token that is unknown, not added by the owner and unpriced is not listed. A priced token, a token the
 * owner added, a testnet token and every known coin stay. With Hide spam off, nothing is left out.
 */
class HomeSpamFilterTest {
    private val eth = NetworkId(Family.EVM, "1", testnet = false)
    private val sepolia = NetworkId(Family.EVM, "11155111", testnet = true)

    private val native = NativeAsset(
        id = NativeAsset.idOf(eth),
        symbol = "ETH",
        name = "Ethereum",
        network = eth,
        balance = BigDecimal.ONE,
    )

    private fun token(
        symbol: String,
        usd: Double?,
        known: Boolean,
        custom: Boolean = false,
        network: NetworkId = eth,
    ) = TokenAsset(
        id = TokenAsset.idOf(network, "0x$symbol"),
        symbol = symbol,
        name = "Ethereum",
        network = network,
        contract = "0x$symbol",
        balance = BigDecimal.ONE,
        usd = usd,
        known = known,
        custom = custom,
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
    fun withHideSpamOnACustomTokenWithNoPriceIsListed() {
        val mine = token("MINE", usd = null, known = false, custom = true)
        assertEquals(listOf(mine), HomeAssets.withoutSpam(listOf(mine), hideSpam = true))
    }

    @Test
    fun withHideSpamOnATestnetTokenWithNoPriceIsListed() {
        val testnet = token("TESTNET", usd = null, known = false, network = sepolia)
        assertEquals(listOf(testnet), HomeAssets.withoutSpam(listOf(testnet), hideSpam = true))
    }

    @Test
    fun aDiscoveredMainnetTokenWithNoPriceIsHiddenOnlyWhenHideSpamIsOn() {
        val discovered = token("DROP", usd = null, known = false)
        assertEquals(emptyList(), HomeAssets.withoutSpam(listOf(discovered), hideSpam = true))
        assertEquals(listOf(discovered), HomeAssets.withoutSpam(listOf(discovered), hideSpam = false))
    }

    @Test
    fun aTokenIsKnownUnlessItIsMarkedUnknown() {
        assertEquals(true, token("ANY", usd = null, known = true).known)
        assertEquals(true, TokenAsset(id = "x", symbol = "X", name = "n", network = eth, contract = "0x1", balance = BigDecimal.ONE).known)
    }
}
