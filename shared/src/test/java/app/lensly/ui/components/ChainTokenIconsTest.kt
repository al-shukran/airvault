package app.lensly.ui.components

import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.TokenEntry
import app.lensly.shared.R
import app.lensly.ui.theme.LenslyLogoBackdrop
import app.lensly.ui.theme.LenslyLogoDarkBackdrop
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun everyLogoInAToneSetIsInTheIconMap() {
        assertEquals(emptySet<String>(), (chainIconsNeedingLightBackdrop + chainIconsNeedingDarkBackdrop) - chainIconDrawables.keys)
        assertEquals(emptySet<String>(), (tokenIconsNeedingLightBackdrop + tokenIconsNeedingDarkBackdrop) - tokenIconDrawables.keys)
    }

    @Test
    fun noLogoIsInBothToneSets() {
        assertEquals(emptySet<String>(), chainIconsNeedingLightBackdrop intersect chainIconsNeedingDarkBackdrop)
        assertEquals(emptySet<String>(), tokenIconsNeedingLightBackdrop intersect tokenIconsNeedingDarkBackdrop)
    }

    @Test
    fun theShippedCatalogNamesLogosForItsNetworksAndTokens() {
        assertTrue(registry.networks().count { it.icon != null } >= 7)
        assertTrue(registry.tokens().count { it.icon != null } >= 10)
    }

    @Test
    fun aNetworkWithALogoShowsItsLogo() {
        assertEquals(R.drawable.chain_base, chainLogo(registry.network(8453L)))
        assertEquals(R.drawable.chain_polygon, chainLogo(registry.network(137L)))
        assertEquals(R.drawable.chain_arbitrum_one, chainLogo(registry.network(42161L)))
    }

    @Test
    fun monadMainnetAndTestnetShowTheMonadLogo() {
        assertEquals(R.drawable.chain_monad, chainLogo(registry.network(143L)))
        assertEquals(R.drawable.chain_monad, chainLogo(registry.network(10143L)))
    }

    @Test
    fun aRecordsNetworkShowsTheLogoOfTheRegistryNetworkOfThatName() {
        assertEquals(R.drawable.chain_monad, networkLogoOf("Monad Testnet", registry.networks()))
        assertEquals(R.drawable.chain_monad, networkLogoOf("Monad", registry.networks()))
        assertEquals(R.drawable.chain_base, networkLogoOf("Base", registry.networks()))
    }

    @Test
    fun aNameNoRegistryNetworkHasNoLogoAndIsNeverTheEthereumLogo() {
        assertNull(networkLogoOf("Unknown network (chain id 999999)", registry.networks()))
        assertNull(networkLogoOf("Solana", registry.networks()))
        assertNull(networkLogoOf("Algorand", registry.networks()))
        assertNull(networkLogoOf(null, registry.networks()))
    }

    @Test
    fun aNameWithNoLogoShowsItsLetterAndAnUnnamedNetworkShowsAQuestionMark() {
        assertEquals("U", monogramOf("Unknown network (chain id 999999)"))
        assertEquals("S", monogramOf("Solana"))
        assertEquals("?", monogramOf(""))
    }

    @Test
    fun aNullNetworkShowsTheEthereumLogo() {
        assertEquals(R.drawable.chain_ethereum, chainLogo(null))
    }

    @Test
    fun aKnownNetworkWithoutALogoShowsAMonogramNotTheEthereumLogo() {
        assertNull(chainLogo(network(icon = null, name = "Monad Testnet")))
    }

    @Test
    fun aKnownNetworkWhoseLogoNameIsUnknownShowsAMonogramNotTheEthereumLogo() {
        assertNull(chainLogo(network(icon = "chain_not_shipped")))
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

    @Test
    fun aDarkLogoSitsOnALightDiscOnlyOnADarkPage() {
        assertEquals(LogoTone.DARK, logoTone(R.drawable.chain_base))
        assertEquals(LenslyLogoBackdrop, backdropColor(LogoTone.DARK, onDarkPage = true))
        assertNull(backdropColor(LogoTone.DARK, onDarkPage = false))
    }

    @Test
    fun aPaleLogoSitsOnADarkDiscOnlyOnALightPage() {
        assertEquals(LogoTone.PALE, logoTone(R.drawable.token_wbtc))
        assertEquals(LenslyLogoDarkBackdrop, backdropColor(LogoTone.PALE, onDarkPage = false))
        assertNull(backdropColor(LogoTone.PALE, onDarkPage = true))
    }

    @Test
    fun theUnknownTokenIconIsPaleSoItShowsOnALightPage() {
        assertEquals(LogoTone.PALE, logoTone(R.drawable.ic_unknown_token))
    }

    @Test
    fun theAlgorandMarkSitsOnALightDiscOnlyOnADarkPage() {
        assertEquals(LogoTone.DARK, logoTone(R.drawable.ic_algorand))
        assertEquals(LenslyLogoBackdrop, backdropColor(LogoTone.DARK, onDarkPage = true))
        assertNull(backdropColor(LogoTone.DARK, onDarkPage = false))
    }

    @Test
    fun aBrightLogoIsNeutralAndNeedsNoDiscOnEitherPage() {
        assertEquals(LogoTone.NEUTRAL, logoTone(R.drawable.chain_arbitrum_one))
        assertEquals(LogoTone.NEUTRAL, logoTone(R.drawable.chain_ethereum))
        assertEquals(LogoTone.NEUTRAL, logoTone(R.drawable.ic_solana))
        assertNull(backdropColor(LogoTone.NEUTRAL, onDarkPage = true))
        assertNull(backdropColor(LogoTone.NEUTRAL, onDarkPage = false))
    }

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
