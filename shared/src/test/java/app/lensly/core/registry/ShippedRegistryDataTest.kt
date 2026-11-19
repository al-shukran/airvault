package app.lensly.core.registry

import app.airvault.chains.evm.evmManifest
import app.lensly.core.registry.update.RegistryUpdate
import app.lensly.core.registry.update.TrustedKeys
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
    fun theShippedWhitelistJsonStaysEmptySoEveryApprovalComesFromTheSignedBundle() {
        assertTrue(registry.whitelistEntries().isEmpty(), "whitelist.json must ship empty; approvals ship in registry/bundled.lrp only")
    }

    @Test
    fun theBundledPackageVerifiesWithThePinnedReleaseKeyAndApprovesExactlyTheVerifiedCalls() {
        val releaseOnly = TrustedKeys.load { path -> File(MAIN, path).takeIf { it.isFile }?.readText() }
        val bytes = File(MAIN, BUNDLED_PATH).readBytes()

        val bundled = RegistryUpdate.verify(bytes, releaseOnly, registry).getOrThrow()

        assertEquals(1L, bundled.sequence)
        assertEquals(VERIFIED_CALLS, bundled.whitelist.map { "${it.chainId} ${it.contract} ${it.selector}" }.sorted())
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
    fun onlyTheMonadTestnetIsInTheMainAssets() {
        // Monad testnet is the project's default testnet, so the release registry ships it. Every other testnet is debug-only.
        assertEquals(listOf(MONAD_TESTNET), registry.networks().filter { it.testnet }.map { it.chainId })
        assertEquals(1, TESTNET_TRUE.findAll(networksText).count(), "testnet: true in networks.json must name only Monad testnet")
        assertTrue(!TESTNET_TRUE.containsMatchIn(tokensText), "testnet flag in tokens.json")
    }

    @Test
    fun everyEvmPrimaryNetworkIsInTheReleaseRegistry() {
        // The primary network of a side is where an account's native coin is read, so a release build must list it.
        for (primary in evmManifest.networks.filter { it.primary }) {
            val network = registry.network(primary.chainId.caip2.substringAfter(':').toLong())
            assertTrue(network != null, "${primary.chainId} is primary in the EVM manifest but is not in networks.json")
            assertEquals(primary.testnet, network?.testnet, "${primary.chainId}: testnet flag differs from the EVM manifest")
        }
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
        val debugOnly = debug.networks().filter { it.testnet }.map { it.chainId }.toSet() - MONAD_TESTNET
        val leaked = registry.networks().map { it.chainId }.filter { it in debugOnly }

        assertEquals(emptyList(), leaked, "debug-only testnet chain IDs in the main networks.json")
    }

    private companion object {
        const val MAIN = "src/main/assets"
        const val DEBUG = "src/debug/assets"
        const val BUNDLED_PATH = "registry/bundled.lrp"
        const val MONAD_TESTNET = 10143L
        val CONTRACT = Regex("0x[0-9a-f]{40}")
        val TESTNET_TRUE = Regex("\"testnet\"\\s*:\\s*true")

        /** The calls tools/registry-pack/sign-candidate.sh signs (verified-selectors.txt), as chainId, contract and selector. */
        val VERIFIED_CALLS = listOf(
            "1 0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad 0x3593564c",
            "1 0x66a9893cc07d91d95644aedd05d03f95e1dba8af 0x3593564c",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0x04e45aaf",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0x09b81346",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0x12210e8a",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0x49404b7c",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0x5023b4df",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0x5ae401dc",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0xac9650d8",
            "1 0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45 0xb858183f",
            "1 0x9008d19f58aabd9ed0d60971565aa8510560ab41 0xec6cb13f",
        ).sorted()
    }
}
