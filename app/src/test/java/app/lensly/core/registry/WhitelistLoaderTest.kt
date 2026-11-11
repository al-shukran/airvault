package app.lensly.core.registry

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val CONTRACT = "0x5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"
private const val OTHER_CONTRACT = "0x6666666666666666666666666666666666666666"

/** A JSON whitelist entry: the Signature, its selector and the approved meaning, as the assets hold them. */
private fun whitelistJson(chainId: Long, contract: String, signature: String, selector: String, meaning: String) =
    """{"chainId":$chainId,"contract":"$contract","selector":"$selector","signature":"$signature","meaning":"$meaning"}"""

/**
 * The whitelist as [RegistryLoader] reads it from the baked assets and the debug overlay: which entries a build approves,
 * what an absent list means, and which lists are refused.
 */
class WhitelistLoaderTest {

    @Test
    fun theWhitelistAssetIsReadAndItsEntryIsFoundByItsChainContractAndSelector() {
        val registry = release()

        val entry = assertNotNull(registry.whitelist(1L, CONTRACT, TRANSFER_SELECTOR))
        assertEquals(TRANSFER_SIGNATURE, entry.signature)
        assertEquals("Send a fixed amount of the token", entry.meaning)
        assertNull(entry.proxy)
    }

    @Test
    fun aProxyEntryKeepsTheImplementationItIsBoundTo() {
        val registry = RegistryLoader.load(
            fakeReader(
                RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                RegistryLoader.WHITELIST_PATH to """
                    {"whitelist":[{"chainId":1,"contract":"$CONTRACT","selector":"$TRANSFER_SELECTOR",
                    "signature":"$TRANSFER_SIGNATURE","meaning":"Send a fixed amount of the token",
                    "proxy":{"implementationAddress":"$IMPLEMENTATION"}}]}
                """.trimIndent(),
            ),
        )

        val proxy = assertNotNull(registry.whitelist(1L, CONTRACT, TRANSFER_SELECTOR)?.proxy)
        assertEquals(IMPLEMENTATION, proxy.implementationAddress)
        assertNull(proxy.implementationCodeHash)
    }

    @Test
    fun theDebugOverlayAddsItsEntryOnADebugChain() {
        val release = release()
        val debug = debug()

        // The debug build holds the build's entry and the overlay's; the release build holds only the first.
        assertEquals(listOf(TRANSFER_SELECTOR), release.whitelistEntries().map { it.selector })
        assertNull(release.whitelist(SEPOLIA, CONTRACT, APPROVE_SELECTOR))
        assertNotNull(debug.whitelist(1L, CONTRACT, TRANSFER_SELECTOR))
        assertNotNull(debug.whitelist(SEPOLIA, CONTRACT, APPROVE_SELECTOR))
        assertEquals(listOf(TRANSFER_SELECTOR, APPROVE_SELECTOR), debug.whitelistEntries().map { it.selector })
    }

    @Test
    fun aBuildWithoutAWhitelistApprovesNoCall() {
        val registry = RegistryLoader.load(
            fakeReader(
                RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
            ),
        )

        assertTrue(registry.whitelistEntries().isEmpty())
        assertNull(registry.whitelist(1L, CONTRACT, TRANSFER_SELECTOR))
    }

    @Test
    fun aDebugOverlayWithoutTheBuildWhitelistStillLoads() {
        val registry = RegistryLoader.load(
            fakeReader(
                RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                RegistryLoader.NETWORKS_DEBUG_PATH to NETWORKS_DEBUG,
                RegistryLoader.WHITELIST_DEBUG_PATH to DEBUG_WHITELIST,
            ),
        )

        assertEquals(listOf(APPROVE_SELECTOR), registry.whitelistEntries().map { it.selector })
        assertNotNull(registry.whitelist(SEPOLIA, CONTRACT, APPROVE_SELECTOR))
    }

    @Test
    fun anEntryOnAChainNoNetworkHoldsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                    RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                    RegistryLoader.WHITELIST_PATH to
                        """{"whitelist":[${whitelistJson(SEPOLIA, CONTRACT, TRANSFER_SIGNATURE, TRANSFER_SELECTOR, "Nowhere")}]}""",
                ),
            )
        }
    }

    @Test
    fun aRepeatedEntryInOneAssetIsRejected() {
        val entry = whitelistJson(1L, CONTRACT, TRANSFER_SIGNATURE, TRANSFER_SELECTOR, "Send a fixed amount of the token")

        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                    RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                    RegistryLoader.WHITELIST_PATH to """{"whitelist":[$entry,$entry]}""",
                ),
            )
        }
    }

    @Test
    fun theDebugOverlayCannotRepeatAnEntryOfTheBuild() {
        val shared = whitelistJson(1L, CONTRACT, TRANSFER_SIGNATURE, TRANSFER_SELECTOR, "Send a fixed amount of the token")

        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                    RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                    RegistryLoader.WHITELIST_PATH to """{"whitelist":[$shared]}""",
                    RegistryLoader.WHITELIST_DEBUG_PATH to """{"whitelist":[$shared]}""",
                ),
            )
        }
    }

    @Test
    fun aSelectorThatIsNotItsSignaturesIsRejectedAtLoad() {
        val mislabelled = whitelistJson(1L, CONTRACT, TRANSFER_SIGNATURE, APPROVE_SELECTOR, "Mislabelled")

        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                    RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                    RegistryLoader.WHITELIST_PATH to """{"whitelist":[$mislabelled]}""",
                ),
            )
        }
    }

    @Test
    fun aWhitelistAssetMissingAFieldIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            RegistryLoader.load(
                fakeReader(
                    RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                    RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                    RegistryLoader.WHITELIST_PATH to """{"whitelist":[{"chainId":1}]}""",
                ),
            )
        }
    }

    @Test
    fun entriesAreListedInChainThenContractThenSelectorOrder() {
        val registry = RegistryLoader.load(
            fakeReader(
                RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
                RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
                RegistryLoader.WHITELIST_PATH to """
                    {"whitelist":[
                      ${whitelistJson(1L, OTHER_CONTRACT, BALANCE_SIGNATURE, BALANCE_SELECTOR, "Read a balance")},
                      ${whitelistJson(1L, CONTRACT, TRANSFER_SIGNATURE, TRANSFER_SELECTOR, "Send a fixed amount of the token")},
                      ${whitelistJson(1L, CONTRACT, APPROVE_SIGNATURE, APPROVE_SELECTOR, "Grant an allowance")}
                    ]}
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(CONTRACT to APPROVE_SELECTOR, CONTRACT to TRANSFER_SELECTOR, OTHER_CONTRACT to BALANCE_SELECTOR),
            registry.whitelistEntries().map { it.contract to it.selector },
        )
    }

    /** The baked catalogs plus the whitelist the release build ships. */
    private fun release(): Registry = RegistryLoader.load(
        fakeReader(
            RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
            RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
            RegistryLoader.WHITELIST_PATH to RELEASE_WHITELIST,
        ),
    )

    /** The same catalogs, the debug network overlay, and the whitelist the debug build ships. */
    private fun debug(): Registry = RegistryLoader.load(
        fakeReader(
            RegistryLoader.NETWORKS_PATH to NETWORKS_MAIN,
            RegistryLoader.TOKENS_PATH to TOKENS_MAIN,
            RegistryLoader.WHITELIST_PATH to RELEASE_WHITELIST,
            RegistryLoader.NETWORKS_DEBUG_PATH to NETWORKS_DEBUG,
            RegistryLoader.WHITELIST_DEBUG_PATH to DEBUG_WHITELIST,
        ),
    )

    private companion object {
        const val TRANSFER_SIGNATURE = "transfer(address,uint256)"
        const val TRANSFER_SELECTOR = "0xa9059cbb"
        const val APPROVE_SIGNATURE = "approve(address,uint256)"
        const val APPROVE_SELECTOR = "0x095ea7b3"
        const val BALANCE_SIGNATURE = "balanceOf(address)"
        const val BALANCE_SELECTOR = "0x70a08231"
        const val IMPLEMENTATION = "0x7777777777777777777777777777777777777777"
        const val SEPOLIA = 11155111L

        val NETWORKS_MAIN = """
            {"networks":[{"chainId":1,"name":"Ethereum","nativeSymbol":"ETH","feeModel":"L1","explorerName":"Etherscan","testnet":false}]}
        """.trimIndent()

        val TOKENS_MAIN = """{"tokens":[]}"""

        val NETWORKS_DEBUG = """
            {"networks":[{"chainId":11155111,"name":"Ethereum Sepolia","nativeSymbol":"ETH","feeModel":"L1","explorerName":"Etherscan","testnet":true}]}
        """.trimIndent()

        val RELEASE_WHITELIST = """{"whitelist":[${
            whitelistJson(1L, CONTRACT, TRANSFER_SIGNATURE, TRANSFER_SELECTOR, "Send a fixed amount of the token")
        }]}"""

        val DEBUG_WHITELIST = """{"whitelist":[${
            whitelistJson(SEPOLIA, CONTRACT, APPROVE_SIGNATURE, APPROVE_SELECTOR, "Grant an allowance")
        }]}"""
    }
}

/** Simulates the APK layout: each path is read from the map, and an absent path reads as null. */
private fun fakeReader(vararg files: Pair<String, String>): (String) -> String? {
    val byPath = files.toMap()
    return { path -> byPath[path] }
}
