package app.lensly.core.registry

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The optional `icon` field: catalogs written before logos existed still load, and an icon is read when present. */
class RegistryIconFieldTest {
    private fun load(networks: String, tokens: String): Registry = RegistryLoader.load { path ->
        when (path) {
            RegistryLoader.NETWORKS_PATH -> networks
            RegistryLoader.TOKENS_PATH -> tokens
            else -> null
        }
    }

    @Test
    fun aCatalogWithoutIconFieldsStillLoadsWithNoLogos() {
        val registry = load(NETWORKS_WITHOUT_ICON, TOKENS_WITHOUT_ICON)

        assertNull(registry.network(8453L)?.icon)
        assertNull(registry.token(8453L, USDC_BASE)?.icon)
    }

    @Test
    fun anExplicitNullIconLoadsAsNoLogo() {
        val registry = load(NETWORKS_WITH_NULL_ICON, TOKENS_WITH_NULL_ICON)

        assertNull(registry.network(8453L)?.icon)
        assertNull(registry.token(8453L, USDC_BASE)?.icon)
    }

    @Test
    fun anIconNameIsReadWhenPresent() {
        val registry = load(NETWORKS_WITH_ICON, TOKENS_WITH_ICON)

        assertEquals("chain_base", registry.network(8453L)?.icon)
        assertEquals("token_usdc", registry.token(8453L, USDC_BASE)?.icon)
    }

    private companion object {
        const val USDC_BASE = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"

        const val BASE_NETWORK =
            """{"chainId":8453,"name":"Base","nativeSymbol":"ETH","nativeDecimals":18,"feeModel":"L2_ROLLUP",""" +
                """"explorerName":"Basescan","testnet":false"""
        const val USDC_TOKEN =
            """{"chainId":8453,"contract":"$USDC_BASE","symbol":"USDC","name":"USD Coin","decimals":6"""

        val NETWORKS_WITHOUT_ICON = """{"networks":[$BASE_NETWORK}]}"""
        val NETWORKS_WITH_NULL_ICON = """{"networks":[$BASE_NETWORK,"icon":null}]}"""
        val NETWORKS_WITH_ICON = """{"networks":[$BASE_NETWORK,"icon":"chain_base"}]}"""
        val TOKENS_WITHOUT_ICON = """{"tokens":[$USDC_TOKEN}]}"""
        val TOKENS_WITH_NULL_ICON = """{"tokens":[$USDC_TOKEN,"icon":null}]}"""
        val TOKENS_WITH_ICON = """{"tokens":[$USDC_TOKEN,"icon":"token_usdc"}]}"""
    }
}
