package app.lensly.app.sign

import app.airvault.chains.algorand.AlgorandNetwork
import app.lensly.core.algorand.AsaCatalog
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The bundled asset list, `app/src/main/assets/algorand/asa-catalog.json`, read the way the app reads it. The checks are the values the
 * list must hold: USDC has six decimals on mainnet and on testnet, and each network is keyed by the genesis hash Lensly pins for it.
 */
class AsaCatalogFileTest {
    private val text: String by lazy { File(CATALOG_PATH).readText() }
    private val catalog: AsaCatalog by lazy { AsaCatalog.parse(text) }

    @Test
    fun usdcHasSixDecimalsOnMainnetAndOnTestnet() {
        val mainnet = catalog.lookup(AlgorandNetwork.MAINNET, 31_566_704UL)
        val testnet = catalog.lookup(AlgorandNetwork.TESTNET, 10_458_941UL)

        assertEquals("USDC", mainnet?.unitName)
        assertEquals(6, mainnet?.decimals)
        assertEquals("USDC", testnet?.unitName)
        assertEquals(6, testnet?.decimals)
    }

    @Test
    fun eachNetworkIsKeyedByItsPinnedGenesisHash() {
        for (network in AlgorandNetwork.entries) {
            val key = Base64.getEncoder().encodeToString(network.genesisHash)
            assertTrue(text.contains("\"$key\""), "${network.genesisId} is keyed by $key")
        }
    }

    @Test
    fun mainnetListsTheCoinGeckoAssetsAndTestnetListsUsdc() {
        assertTrue(catalog.assetsOn(AlgorandNetwork.MAINNET).size >= 20, "mainnet lists ${catalog.assetsOn(AlgorandNetwork.MAINNET).size}")
        assertEquals(listOf(10_458_941UL), catalog.assetsOn(AlgorandNetwork.TESTNET).map { it.id })
    }

    @Test
    fun everyListIsSortedByAssetIdAndEveryAssetHasAUnitNameAndDecimals() {
        for (network in AlgorandNetwork.entries) {
            val assets = catalog.assetsOn(network)
            assertEquals(assets.sortedBy { it.id }, assets, network.genesisId)
            for (asset in assets) {
                assertTrue(asset.unitName.isNotEmpty(), "asset ${asset.id} has a unit name")
                assertTrue(asset.decimals in 0..19, "asset ${asset.id} has decimals ${asset.decimals}")
            }
        }
    }

    private companion object {
        /** The catalog's path from the app module, where the unit tests run. */
        const val CATALOG_PATH = "src/main/assets/algorand/asa-catalog.json"
    }
}
