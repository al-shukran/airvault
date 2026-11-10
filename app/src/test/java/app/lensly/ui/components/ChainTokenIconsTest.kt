package app.lensly.ui.components

import app.lensly.R
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.TokenEntry
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
    fun aNetworkWithALogoShowsItsLogo() {
        assertEquals(R.drawable.chain_base, chainIconRes(registry.network(8453L)))
    }

    @Test
    fun monadHasNoLogoSoItShowsTheEthereumLogo() {
        assertNull(registry.network(143L)?.icon)
        assertEquals(R.drawable.chain_ethereum, chainIconRes(registry.network(143L)))
    }

    @Test
    fun aNullNetworkShowsTheEthereumLogo() {
        assertEquals(R.drawable.chain_ethereum, chainIconRes(null))
    }

    @Test
    fun aNetworkWithoutALogoShowsTheEthereumLogo() {
        assertEquals(R.drawable.chain_ethereum, chainIconRes(network(icon = null)))
    }

    @Test
    fun aNetworkWhoseLogoNameIsUnknownShowsTheEthereumLogo() {
        assertEquals(R.drawable.chain_ethereum, chainIconRes(network(icon = "chain_not_shipped")))
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

    private fun network(icon: String?) = NetworkEntry(
        chainId = 8453L,
        name = "Base",
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
