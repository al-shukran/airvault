package app.lensly.core.algorand

import app.airvault.chains.algorand.AlgorandNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/**
 * The bundled catalog format that [AsaCatalog.parse] reads. An asset is found only on the network that lists it, because the same ID is a
 * different asset on another network, and a damaged file is refused rather than read as an empty list.
 */
class AsaCatalogTest {

    @Test
    fun anAssetIsFoundOnTheNetworkThatListsIt() {
        val catalog = AsaCatalog.parse(catalogJson(mainnet = listOf(USDC_MAINNET), testnet = listOf(USDC_TESTNET)))

        assertEquals(Asa(31_566_704UL, "USDC", "USDC", 6), catalog.lookup(AlgorandNetwork.MAINNET, 31_566_704UL))
        assertEquals(Asa(10_458_941UL, "USDC", "USDC", 6), catalog.lookup(AlgorandNetwork.TESTNET, 10_458_941UL))
    }

    @Test
    fun anAssetIsNotFoundOnTheOtherNetwork() {
        val catalog = AsaCatalog.parse(catalogJson(mainnet = listOf(USDC_MAINNET), testnet = emptyList()))

        assertNull(catalog.lookup(AlgorandNetwork.TESTNET, 31_566_704UL))
    }

    @Test
    fun theSameIdOnTwoNetworksIsTwoDifferentAssets() {
        val catalog = AsaCatalog.parse(
            catalogJson(
                mainnet = listOf("""{"id": 7, "unit": "MAIN", "name": "Main", "decimals": 0}"""),
                testnet = listOf("""{"id": 7, "unit": "TEST", "name": "Test", "decimals": 2}"""),
            ),
        )

        assertEquals("MAIN", catalog.lookup(AlgorandNetwork.MAINNET, 7UL)?.unitName)
        assertEquals(2, catalog.lookup(AlgorandNetwork.TESTNET, 7UL)?.decimals)
    }

    @Test
    fun theEmptyCatalogNamesNoAsset() {
        assertNull(AsaCatalog.EMPTY.lookup(AlgorandNetwork.MAINNET, 31_566_704UL))
    }

    @Test
    fun aNetworkKeyedByAHashThatIsNotPinnedIsNotLookedUp() {
        val catalog = AsaCatalog.parse(
            """{"networks": {"AAAA": {"genesisId": "other-v1", "assets": [{"id": 1, "unit": "X", "name": "X", "decimals": 0}]}}}""",
        )

        assertNull(catalog.lookup(AlgorandNetwork.MAINNET, 1UL))
        assertNull(catalog.lookup(AlgorandNetwork.TESTNET, 1UL))
    }

    @Test
    fun anAssetListedTwiceOnOneNetworkIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            AsaCatalog.parse(catalogJson(mainnet = listOf(USDC_MAINNET, USDC_MAINNET), testnet = emptyList()))
        }
    }

    @Test
    fun decimalsOutsideZeroToNineteenAndAnEmptyUnitNameAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            AsaCatalog.parse(catalogJson(mainnet = listOf("""{"id": 1, "unit": "X", "name": "X", "decimals": 20}"""), testnet = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            AsaCatalog.parse(catalogJson(mainnet = listOf("""{"id": 1, "unit": "", "name": "X", "decimals": 2}"""), testnet = emptyList()))
        }
    }

    @Test
    fun aFileThatIsNotACatalogIsRefused() {
        assertFails { AsaCatalog.parse("[]") }
    }

    /** A catalog file with one network per genesis hash, as the generator writes it. */
    private fun catalogJson(mainnet: List<String>, testnet: List<String>): String = """
        {"networks": {
          "$MAINNET_HASH": {"genesisId": "mainnet-v1.0", "assets": [${mainnet.joinToString(",")}]},
          "$TESTNET_HASH": {"genesisId": "testnet-v1.0", "assets": [${testnet.joinToString(",")}]}
        }}
    """.trimIndent()

    private companion object {
        /** The genesis hashes Lensly pins for mainnet and testnet, the keys of the catalog. */
        const val MAINNET_HASH = "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8="
        const val TESTNET_HASH = "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI="
        const val USDC_MAINNET = """{"id": 31566704, "unit": "USDC", "name": "USDC", "decimals": 6}"""
        const val USDC_TESTNET = """{"id": 10458941, "unit": "USDC", "name": "USDC", "decimals": 6}"""
    }
}
