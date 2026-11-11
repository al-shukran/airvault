package app.lensly.ui.components

import app.lensly.R
import app.lensly.core.registry.RegistryLoader
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * The stacked icon: a pegged or bridged copy shows its asset's logo, with the logo of its own chain as a badge. Reads the
 * shipped catalog, so the rules are checked against the tokens the app really ships.
 */
class TokenBadgeTest {
    private val registry = RegistryLoader.load { path ->
        listOf("src/main/assets", "src/debug/assets").map { File(it, path) }.firstOrNull { it.isFile }?.readText()
    }

    @Test
    fun aBinancePeggedUsdtShowsTheUsdtLogoWithTheBnbBadge() {
        val token = registry.token(56L, BINANCE_PEGGED_USDT)
        assertEquals(R.drawable.token_usdt, tokenIconRes(token))
        assertEquals(bnbBadge, tokenBadgeSpec(token, registry.network(56L)))
    }

    @Test
    fun aBinancePeggedEthShowsTheEthereumLogoWithTheBnbBadge() {
        val token = registry.token(56L, BINANCE_PEGGED_ETH)
        assertEquals(R.drawable.chain_ethereum, tokenIconRes(token))
        assertEquals(bnbBadge, tokenBadgeSpec(token, registry.network(56L)))
    }

    @Test
    fun anAvalancheBridgedTokenShowsItsLogoWithTheAvalancheBadge() {
        val token = registry.token(43114L, AAVE_E_AVALANCHE)
        assertEquals(R.drawable.token_aave, tokenIconRes(token))
        assertEquals(avalancheBadge, tokenBadgeSpec(token, registry.network(43114L)))
    }

    @Test
    fun anAvalancheBridgedWrappedEtherShowsTheEthereumLogoWithTheAvalancheBadge() {
        val token = registry.token(43114L, WETH_E_AVALANCHE)
        assertEquals(R.drawable.chain_ethereum, tokenIconRes(token))
        assertEquals(avalancheBadge, tokenBadgeSpec(token, registry.network(43114L)))
    }

    @Test
    fun aNormalTokenShowsItsLogoWithoutABadge() {
        val token = registry.token(1L, USDC_ETHEREUM)
        assertEquals(R.drawable.token_usdc, tokenIconRes(token))
        assertNull(tokenBadgeSpec(token, registry.network(1L)))
    }

    @Test
    fun aBridgedTokenOutsideAvalancheHasNoBadge() {
        assertNull(tokenBadgeSpec(registry.token(10L, USDC_E_OPTIMISM), registry.network(10L)))
    }

    @Test
    fun aCopyHasNoBadgeWithoutItsOwnChain() {
        val token = registry.token(56L, BINANCE_PEGGED_USDT)
        assertNull(tokenBadgeSpec(token, registry.network(1L)))
        assertNull(tokenBadgeSpec(token, null))
    }

    @Test
    fun aNullTokenHasNoBadge() {
        assertNull(tokenBadgeSpec(null, registry.network(56L)))
    }

    private companion object {
        const val BINANCE_PEGGED_USDT = "0x55d398326f99059ff775485246999027b3197955"
        const val BINANCE_PEGGED_ETH = "0x2170ed0880ac9a755fd29b2688956bd959f933f8"
        const val AAVE_E_AVALANCHE = "0x63a72806098bd3d9520cc43356dd78afe5d386d9"
        const val WETH_E_AVALANCHE = "0x49d5c2bdffac6ce2bfdb6640f4f80f226bc10bab"
        const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val USDC_E_OPTIMISM = "0x7f5c764cbc14f9669b88837ca1490cca17c31607"

        val bnbBadge = ChainIconSpec.Logo(R.drawable.chain_binance_smart_chain, backdrop = false)
        val avalancheBadge = ChainIconSpec.Logo(R.drawable.chain_avalanche, backdrop = false)
    }
}
