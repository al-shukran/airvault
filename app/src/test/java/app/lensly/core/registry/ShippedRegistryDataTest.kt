package app.lensly.core.registry

import app.lensly.ui.components.chainIconDrawables
import app.lensly.ui.components.tokenIconDrawables
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Checks the catalogs that ship in the APK (src/main/assets/registry), the files tools/registry-data writes.
 * Paths are relative to :app, which is the working directory for unit tests.
 */
class ShippedRegistryDataTest {
    private val networksText = File(MAIN, "registry/networks.json").readText()
    private val tokensText = File(MAIN, "registry/tokens.json").readText()
    private val whitelistText = File(MAIN, "registry/whitelist.json").readText()
    private val debugNetworksText = File(DEBUG, "registry/networks.debug.json").readText()
    private val debugTokensText = File(DEBUG, "registry/tokens.debug.json").readText()
    private val debugWhitelistText = File(DEBUG, "registry/whitelist.debug.json").readText()

    private val registry: Registry = RegistryLoader.load { path ->
        when (path) {
            RegistryLoader.NETWORKS_PATH -> networksText
            RegistryLoader.TOKENS_PATH -> tokensText
            RegistryLoader.WHITELIST_PATH -> whitelistText
            else -> null
        }
    }

    @Test
    fun theShippedWhitelistApprovesNoCallSoOnlyAnOfflineUpdateCanAddOne() {
        assertTrue(registry.whitelistEntries().isEmpty(), "whitelist.json must ship empty")
    }

    @Test
    fun theDebugWhitelistApprovesOnlyCallsOnDebugTestnets() {
        val debug = RegistryLoader.load { path ->
            when (path) {
                RegistryLoader.NETWORKS_PATH -> networksText
                RegistryLoader.TOKENS_PATH -> tokensText
                RegistryLoader.WHITELIST_PATH -> whitelistText
                RegistryLoader.NETWORKS_DEBUG_PATH -> debugNetworksText
                RegistryLoader.TOKENS_DEBUG_PATH -> debugTokensText
                RegistryLoader.WHITELIST_DEBUG_PATH -> debugWhitelistText
                else -> null
            }
        }

        assertTrue(debug.whitelistEntries().isNotEmpty(), "the debug overlay should carry a test entry")
        assertTrue(
            debug.whitelistEntries().all { debug.network(it.chainId)?.testnet == true },
            "a debug whitelist entry is on a chain that is not a debug testnet",
        )
    }

    @Test
    fun theShippedJsonLoadsThroughRegistryLoaderWithoutErrors() {
        assertTrue(registry.networks().isNotEmpty(), "no networks loaded")
        assertTrue(registry.tokens().isNotEmpty(), "no tokens loaded")
    }

    @Test
    fun everyTokenNamesANetworkInTheCatalog() {
        val orphans = registry.tokens().filter { registry.network(it.chainId) == null }
            .map { "${it.symbol} on ${it.chainId}" }

        assertEquals(emptyList(), orphans)
    }

    @Test
    fun thereAreNoDuplicateChainIds() {
        val ids = registry.networks().map { it.chainId }

        assertEquals(ids.toSet().size, ids.size, "duplicate chainId in networks.json")
    }

    @Test
    fun thereAreNoDuplicateChainContractPairs() {
        val pairs = registry.tokens().map { it.chainId to it.contract }

        assertEquals(pairs.toSet().size, pairs.size, "duplicate (chainId, contract) in tokens.json")
    }

    @Test
    fun everyContractIsLowercase0xFortyHex() {
        val bad = registry.tokens().map { it.contract }.filterNot { CONTRACT.matches(it) }

        assertEquals(emptyList(), bad)
    }

    @Test
    fun everyIconNameResolvesInRegistryIcons() {
        val unknownChainIcons = registry.networks().mapNotNull { it.icon }
            .filterNot { chainIconDrawables.containsKey(it) }
        val unknownTokenIcons = registry.tokens().mapNotNull { it.icon }
            .filterNot { tokenIconDrawables.containsKey(it) }

        assertEquals(emptyList(), unknownChainIcons, "chain icon names missing from RegistryIcons.kt")
        assertEquals(emptyList(), unknownTokenIcons, "token icon names missing from RegistryIcons.kt")
    }

    @Test
    fun noTestnetIsInTheMainAssets() {
        assertTrue(registry.networks().none { it.testnet }, "a testnet row is in networks.json")
        assertTrue(!TESTNET_TRUE.containsMatchIn(networksText), "testnet: true in networks.json")
        assertTrue(!TESTNET_TRUE.containsMatchIn(tokensText), "testnet flag in tokens.json")
    }

    @Test
    fun noDebugOverlayChainIsInTheMainAssets() {
        val debug = RegistryLoader.load { path ->
            when (path) {
                RegistryLoader.NETWORKS_PATH -> networksText
                RegistryLoader.TOKENS_PATH -> tokensText
                RegistryLoader.WHITELIST_PATH -> whitelistText
                RegistryLoader.NETWORKS_DEBUG_PATH -> debugNetworksText
                RegistryLoader.TOKENS_DEBUG_PATH -> debugTokensText
                else -> null
            }
        }
        val debugOnly = debug.networks().filter { it.testnet }.map { it.chainId }.toSet()
        val leaked = registry.networks().map { it.chainId }.filter { it in debugOnly }

        assertEquals(emptyList(), leaked, "debug-only testnet chain IDs in the main networks.json")
    }

    private companion object {
        const val MAIN = "src/main/assets"
        const val DEBUG = "src/debug/assets"
        val CONTRACT = Regex("0x[0-9a-f]{40}")
        val TESTNET_TRUE = Regex("\"testnet\"\\s*:\\s*true")
    }
}
