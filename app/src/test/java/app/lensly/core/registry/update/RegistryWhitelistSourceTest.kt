package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.registry.WhitelistParam
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Paths are relative to :app, the working directory for unit tests. */
private const val MAIN = "src/main/assets"
private const val TEST_CONTRACT = "0x5555555555555555555555555555555555555555"
private const val SWAP_SELECTOR = "0x6e17e9f6"

private fun fileReader(vararg roots: String): (String) -> String? = { path ->
    roots.map { File(it, path) }.firstOrNull { it.isFile }?.readText()
}

/** Which source the effective whitelist comes from: the build's catalog or the installed package, by catalog sequence. */
class RegistryWhitelistSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = listOf(TestPackages.PUBLIC_BYTES)

    private fun dir(): File = folder.root.resolve("registry")

    private fun built(sequence: Long, whitelist: List<WhitelistEntry> = emptyList()): Registry =
        RegistryLoader.load(fileReader(MAIN)).let {
            Registry(it.networks(), it.tokens(), whitelist, catalogSequence = sequence)
        }

    private fun buildEntry() = WhitelistEntry(
        chainId = 1L,
        contract = TEST_CONTRACT,
        selector = SWAP_SELECTOR,
        signature = "swapExactTokensForTokens(uint256,uint256,address)",
        meaning = "Swap along the route",
        params = listOf(
            WhitelistParam("amountIn", "uint256"),
            WhitelistParam("amountOutMin", "uint256"),
            WhitelistParam("to", "address"),
        ),
        upgradeable = false,
    )

    @Test
    fun anEntryRemovedInANewerBuildIsNotApprovedWhileAnOlderPackageIsInstalled() {
        val built = built(sequence = 5)
        val dir = dir()
        RegistryUpdate.install(
            TestPackages.signed(TestPackages.payload(sequence = 3, whitelist = TestPackages.LINEA_SWAP)),
            keys,
            built,
            dir,
        ).getOrThrow()

        val merged = RegistryUpdate.withInstalled(built, dir, keys)

        assertNull(merged.whitelist(4242L, TEST_CONTRACT, SWAP_SELECTOR))
        assertNotNull(merged.network(4242L))
        assertFalse(merged.installedUpdateUnverified)
    }

    @Test
    fun anInstalledPackageThatFailsVerificationLeavesTheBuiltInWhitelistAndFlagsIt() {
        val entry = buildEntry()
        val built = built(sequence = 5, whitelist = listOf(entry))
        val dir = dir().apply { mkdirs() }
        val withAnotherKey = TestPackages.signed(TestPackages.payload(sequence = 8), seed = ByteArray(32) { 9 })
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(withAnotherKey)

        val merged = RegistryUpdate.withInstalled(built, dir, keys)

        assertTrue(merged.installedUpdateUnverified)
        assertEquals(entry, merged.whitelist(1L, TEST_CONTRACT, SWAP_SELECTOR))
    }

    @Test
    fun aTieOnTheSequenceKeepsTheBuiltWhitelist() {
        val built = built(sequence = 5)
        val dir = dir()
        RegistryUpdate.install(
            TestPackages.signed(TestPackages.payload(sequence = 5, whitelist = TestPackages.LINEA_SWAP)),
            keys,
            built,
            dir,
        ).getOrThrow()

        val merged = RegistryUpdate.withInstalled(built, dir, keys)

        assertNull(merged.whitelist(4242L, TEST_CONTRACT, SWAP_SELECTOR))
        assertFalse(merged.installedUpdateUnverified)
    }
}
