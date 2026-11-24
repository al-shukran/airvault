package app.lumio

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lumio.evm.CustomChain
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettings
import app.lumio.network.NetworkMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The All N count of the Explore tab: the networks of the side that the owner has switched on, custom chains included. */
class ExploreNetworkCountTest {
    private val registry: Registry = RegistryLoader.load { path ->
        listOf("../shared/src/main/assets", "../shared/src/debug/assets")
            .map { File(it, path) }
            .firstOrNull(File::isFile)
            ?.readText()
    }

    private val mainnetIds = EvmCatalog.chains(registry, EvmSettings()).filter { !it.testnet }.map { it.chainId }.toSet()

    @Test
    fun aNetworkSwitchedOffIsNotCounted() {
        val settings = EvmSettings(enabled = mainnetIds - mainnetIds.first())

        assertEquals(mainnetIds.size - 1, networksOn(registry, settings, NetworkMode.MAINNET))
    }

    @Test
    fun aCustomChainSwitchedOnIsCounted() {
        val custom = CustomChain(424242L, "Example", "EXM", testnet = false, explorer = null)
        val settings = EvmSettings(enabled = mainnetIds + custom.chainId, custom = listOf(custom))

        assertEquals(mainnetIds.size + 1, networksOn(registry, settings, NetworkMode.MAINNET))
    }

    @Test
    fun aTestnetSwitchedOffIsNotCountedOnTheTestnetSide() {
        val settings = EvmSettings(enabled = mainnetIds)

        assertEquals(0, networksOn(registry, settings, NetworkMode.TESTNET))
    }
}
