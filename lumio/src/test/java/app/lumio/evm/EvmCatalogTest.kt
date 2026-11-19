package app.lumio.evm

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chain table that Lumio lists, read from the same catalogs a debug build ships: the shipped mainnets and the debug overlay. */
class EvmCatalogTest {
    private val registry: Registry = RegistryLoader.load { path -> asset(path) }

    /** A catalog file: the shipped one when the shipped set has it, else the debug overlay. Unit tests run from :lumio. */
    private fun asset(path: String): String? {
        val file = listOf("../shared/src/main/assets", "../shared/src/debug/assets")
            .map { File(it, path) }
            .firstOrNull(File::isFile)
        return file?.readText()
    }

    @Test
    fun monadTestnetIsListedAsATestnetChain() {
        val chains = EvmCatalog.chains(registry, EvmSettings())

        val monad = chains.single { it.chainId == 10143L }
        assertEquals("Monad Testnet", monad.name)
        assertTrue(monad.testnet)
    }

    /** The release catalog alone: the shipped networks, without the debug overlay. */
    private val releaseRegistry: Registry = RegistryLoader.load { path ->
        File("../shared/src/main/assets", path).takeIf(File::isFile)?.readText()
    }

    @Test
    fun aReleaseBuildListsThePrimaryTestnetSoAnEthereumAccountShowsItsNativeCoin() {
        val primary = EvmCatalog.primaryChainId(testnet = true)

        assertEquals(10143L, primary)
        assertTrue(EvmCatalog.chains(releaseRegistry, EvmSettings()).any { it.testnet && it.chainId == primary })
    }

    @Test
    fun monadMainnetIsListedAsAMainnetChain() {
        val chains = EvmCatalog.chains(registry, EvmSettings())

        assertFalse(chains.single { it.chainId == 143L }.testnet)
    }
}
