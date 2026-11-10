package app.lensly.core.registry

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Paths are relative to the :app module, which is the working directory for unit tests. */
private const val MAIN = "src/main/assets"
private const val DEBUG = "src/debug/assets"

private const val USDC_ETHEREUM = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val SEPOLIA_USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"

/** Simulates the APK layout: each path is read from the first root that contains it. */
private fun fileReader(vararg roots: String): (String) -> String? = { path ->
    roots.map { File(it, path) }.firstOrNull { it.isFile }?.readText()
}

private fun fakeReader(vararg files: Pair<String, String>): (String) -> String? {
    val byPath = files.toMap()
    return { path -> byPath[path] }
}

class RegistryTest {
    @Test
    fun releaseCatalogLoadsMainnetsOnly() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        assertEquals(
            listOf(
                1L, 10L, 25L, 30L, 40L, 56L, 61L, 100L, 122L, 130L, 137L, 143L, 146L, 169L, 196L, 232L, 250L,
                252L, 324L, 369L, 388L, 480L, 999L, 1088L, 1101L, 1284L, 1285L, 1329L, 1868L, 2020L, 2222L,
                2741L, 3338L, 4326L, 5000L, 8217L, 8453L, 9745L, 13371L, 33139L, 42161L, 42170L, 42220L, 43114L,
                48900L, 57073L, 59144L, 60808L, 80094L, 167000L, 534352L, 747474L, 7777777L, 1313161554L,
                1666600000L,
            ),
            registry.networks().map { it.chainId },
        )
        assertTrue(registry.networks().none { it.testnet })
        assertEquals(249, registry.tokens().size)
        assertNull(registry.network(10143L))
        assertNull(registry.network(11155111L))
        assertNull(registry.token(11155111L, SEPOLIA_USDC))
    }

    @Test
    fun releaseCatalogTokensAllReferenceKnownNetworks() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        assertTrue(registry.tokens().all { registry.network(it.chainId) != null })
    }

    @Test
    fun unknownChainReturnsNull() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        assertNull(registry.network(999_999L))
        assertNull(registry.network(0L))
        assertNull(registry.network(-1L))
    }

    @Test
    fun knownChainResolvesNativeCurrency() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        val ethereum = assertNotNull(registry.network(1L))
        assertEquals("ETH", ethereum.nativeSymbol)
        assertEquals(18, ethereum.nativeDecimals)
        assertEquals(FeeModel.L1, ethereum.feeModel)
        assertEquals(FeeModel.L2_ROLLUP, assertNotNull(registry.network(8453L)).feeModel)
        assertEquals("MON", assertNotNull(registry.network(143L)).nativeSymbol)
    }

    @Test
    fun tokenLookupIsCaseInsensitiveOnInput() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        val fromChecksum = assertNotNull(registry.token(1L, USDC_ETHEREUM))
        val fromUpper = registry.token(1L, USDC_ETHEREUM.uppercase().replaceFirst("0X", "0x"))
        val fromLower = registry.token(1L, USDC_ETHEREUM.lowercase())

        assertEquals("USDC", fromChecksum.symbol)
        assertEquals(6, fromChecksum.decimals)
        assertEquals(fromChecksum, fromUpper)
        assertEquals(fromChecksum, fromLower)
    }

    @Test
    fun tokenAbsentFromRegistryReturnsNull() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        assertNull(registry.token(1L, "0x0000000000000000000000000000000000000001"))
        // USDC exists on Ethereum but not on BNB Smart Chain, so the same token lookup on chain 56 is refused.
        assertNull(registry.token(56L, USDC_ETHEREUM))
        assertNull(registry.token(999_999L, USDC_ETHEREUM))
    }

    @Test
    fun tokenDecimalsComeFromRegistry() {
        val registry = RegistryLoader.load(fileReader(MAIN))

        assertEquals(18, assertNotNull(registry.token(1L, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")).decimals)
        assertEquals(6, assertNotNull(registry.token(43114L, "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7")).decimals)
    }

    @Test
    fun debugOverlayAddsTestnets() {
        val registry = RegistryLoader.load(fileReader(MAIN, DEBUG))

        assertEquals(57, registry.networks().size)
        assertEquals(250, registry.tokens().size)
        assertTrue(assertNotNull(registry.network(10143L)).testnet)
        assertTrue(assertNotNull(registry.network(11155111L)).testnet)
        assertEquals("USDC", assertNotNull(registry.token(11155111L, SEPOLIA_USDC)).symbol)
        assertFalse(assertNotNull(registry.network(1L)).testnet)
    }

    @Test
    fun simulatedDebugAndReleaseLoadersDiffOnlyByOverlay() {
        val main = mapOf(
            RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
            RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
        )
        val release = RegistryLoader.load(fakeReader(*main.toList().toTypedArray()))
        val debug = RegistryLoader.load(
            fakeReader(
                *main.toList().toTypedArray(),
                RegistryLoader.NETWORKS_DEBUG_PATH to NETWORKS_DEBUG,
            ),
        )

        assertNull(release.network(10143L))
        assertEquals(listOf(1L), release.networks().map { it.chainId })
        assertEquals(listOf(1L, 10143L), debug.networks().map { it.chainId })
        assertNull(debug.token(10143L, SEPOLIA_USDC))
    }

    @Test
    fun overlayReplacesMainEntryWithSameChainId() {
        val registry = RegistryLoader.load(
            fakeReader(
                RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                RegistryLoader.NETWORKS_DEBUG_PATH to """
                    {"networks":[{"chainId":1,"name":"Override","nativeSymbol":"ETH","feeModel":"L1","explorerName":null,"testnet":true}]}
                """.trimIndent(),
            ),
        )

        val ethereum = assertNotNull(registry.network(1L))
        assertEquals("Override", ethereum.name)
        assertEquals(18, ethereum.nativeDecimals)
        assertEquals(1, registry.networks().size)
    }

    @Test
    fun missingMainCatalogFailsLoudly() {
        assertFailsWith<IllegalStateException> {
            RegistryLoader.load(fakeReader(RegistryLoader.TOKENS_PATH to TOKENS_MAIN))
        }
    }

    @Test
    fun tokenOnUnknownChainIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                    RegistryLoader.TOKENS_PATH to """
                        {"tokens":[{"chainId":777,"contract":"0x0000000000000000000000000000000000000001","symbol":"X","name":"X","decimals":18}]}
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun duplicateNetworkInOneFileIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to """
                        {"networks":[
                          {"chainId":1,"name":"A","nativeSymbol":"ETH","feeModel":"L1","explorerName":null,"testnet":false},
                          {"chainId":1,"name":"B","nativeSymbol":"ETH","feeModel":"L1","explorerName":null,"testnet":false}
                        ]}
                    """.trimIndent(),
                    RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                ),
            )
        }
    }

    @Test
    fun uppercaseTokenContractInCatalogIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            TokenEntry(1L, USDC_ETHEREUM, "USDC", "USD Coin", 6)
        }
    }

    private companion object {
        val NETWORKS_MAIN = """
            {"networks":[{"chainId":1,"name":"Ethereum","nativeSymbol":"ETH","feeModel":"L1","explorerName":"Etherscan","testnet":false}]}
        """.trimIndent()

        val TOKENS_MAIN = """{"tokens":[]}"""

        val NETWORKS_DEBUG = """
            {"networks":[{"chainId":10143,"name":"Monad Testnet","nativeSymbol":"MON","feeModel":"L1","explorerName":"MonadVision","testnet":true}]}
        """.trimIndent()
    }
}
