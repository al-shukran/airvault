package app.lensly.ui.components

import app.lensly.shared.R
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.TokenEntry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Reads the shipped catalog and the debug overlay, both from the APK assets. Paths are relative to :app, the working
 * directory for unit tests.
 */
class ChainTokenIconsTest {
    private val registry = RegistryLoader.load { path ->
        listOf("src/main/assets", "src/debug/assets").map { File(it, path) }.firstOrNull { it.isFile }?.readText()
    }

    @Test
    fun everyLogoNameInTheShippedRegistryResolvesToADrawable() {
        val missingChainLogos = registry.networks().mapNotNull { it.icon }.filterNot(chainIconDrawables::containsKey)
        val missingTokenLogos = registry.tokens().mapNotNull { it.icon }.filterNot(tokenIconDrawables::containsKey)
        assertEquals(emptyList(), missingChainLogos)
        assertEquals(emptyList(), missingTokenLogos)
    }

    @Test
    fun everyChainLogoOnABackdropIsInTheIconMap() {
        assertEquals(emptySet<String>(), chainIconsNeedingBackdrop - chainIconDrawables.keys)
    }

    @Test
    fun everyTokenLogoOnABackdropIsInTheIconMap() {
        assertEquals(emptySet<String>(), tokenIconsNeedingBackdrop - tokenIconDrawables.keys)
    }

    @Test
    fun theShippedCatalogNamesLogosForItsNetworksAndTokens() {
        assertTrue(registry.networks().count { it.icon != null } >= 7)
        assertTrue(registry.tokens().count { it.icon != null } >= 10)
    }

    @Test
    fun aNetworkWithALogoShowsItsLogoOnABackdropWhenNeeded() {
        assertEquals(logo(R.drawable.chain_base, backdrop = true), chainIconSpec(registry.network(8453L)))
        assertEquals(logo(R.drawable.chain_polygon, backdrop = true), chainIconSpec(registry.network(137L)))
        assertEquals(logo(R.drawable.chain_arbitrum_one), chainIconSpec(registry.network(42161L)))
    }

    @Test
    fun monadMainnetAndTestnetShowTheMonadLogo() {
        assertEquals(logo(R.drawable.chain_monad), chainIconSpec(registry.network(143L)))
        assertEquals(logo(R.drawable.chain_monad), chainIconSpec(registry.network(10143L)))
    }

    @Test
    fun aNullNetworkShowsTheEthereumLogo() {
        assertEquals(logo(R.drawable.chain_ethereum), chainIconSpec(null))
    }

    @Test
    fun aKnownNetworkWithoutALogoShowsAMonogramNotTheEthereumLogo() {
        assertEquals(ChainIconSpec.Monogram("M"), chainIconSpec(network(icon = null, name = "Monad Testnet")))
    }

    @Test
    fun aKnownNetworkWhoseLogoNameIsUnknownShowsAMonogramNotTheEthereumLogo() {
        assertEquals(ChainIconSpec.Monogram("B"), chainIconSpec(network(icon = "chain_not_shipped")))
    }

    @Test
    fun aMonogramIsTheFirstLetterOfTheNetworkName() {
        assertEquals("M", monogramOf("Monad Testnet"))
        assertEquals("0", monogramOf("0G Galileo"))
        assertEquals("T", monogramOf("(Test) chain"))
        assertEquals("?", monogramOf(" "))
    }

    @Test
    fun aTokenWithALogoShowsItsLogo() {
        assertEquals(R.drawable.token_usdc, tokenIconRes(registry.token(1L, USDC_ETHEREUM)))
    }

    @Test
    fun aNullTokenShowsTheUnknownTokenIcon() {
        assertEquals(R.drawable.ic_unknown_token, tokenIconRes(null))
    }

    @Test
    fun aTokenWithoutALogoShowsTheUnknownTokenIcon() {
        assertEquals(R.drawable.ic_unknown_token, tokenIconRes(token(icon = null)))
    }

    @Test
    fun aTokenWhoseLogoNameIsUnknownShowsTheUnknownTokenIcon() {
        assertEquals(R.drawable.ic_unknown_token, tokenIconRes(token(icon = "token_not_shipped")))
    }

    private fun logo(res: Int, backdrop: Boolean = false) = ChainIconSpec.Logo(res, backdrop)

    private fun network(icon: String?, name: String = "Base") = NetworkEntry(
        chainId = 8453L,
        name = name,
        nativeSymbol = "ETH",
        feeModel = FeeModel.L2_ROLLUP,
        explorerName = "Basescan",
        testnet = false,
        icon = icon,
    )

    private fun token(icon: String?) = TokenEntry(
        chainId = 1L,
        contract = USDC_ETHEREUM,
        symbol = "USDC",
        name = "USD Coin",
        decimals = 6,
        icon = icon,
    )

    private companion object {
        const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    }
}
