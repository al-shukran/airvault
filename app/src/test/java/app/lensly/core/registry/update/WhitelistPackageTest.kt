package app.lensly.core.registry.update

import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.registry.WhitelistParam
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
private const val APPROVE_SELECTOR = "0x095ea7b3"
private const val PROXY_ENTRY = """{"chainId":4242,"contract":"0x6666666666666666666666666666666666666666","meaning":"Stake for a recipient","params":[{"name":"amount","type":"uint256","tokenRef":true},{"name":"recipient","type":"address"}],"proxy":{"implementationAddress":"0x7777777777777777777777777777777777777777","implementationCodeHash":null},"selector":"0x7acb7757","signature":"stake(uint256,address)","upgradeable":true}"""

private fun failure(result: Result<*>): Throwable = assertNotNull(result.exceptionOrNull())

class WhitelistPackageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED
    private val baked: Registry = RegistryLoader.load { path -> File(MAIN, path).takeIf(File::isFile)?.readText() }

    private fun dir(): File = folder.root.resolve("registry")

    private fun signed(sequence: Long = 1, whitelist: String? = TestPackages.LINEA_SWAP): ByteArray =
        TestPackages.signed(TestPackages.payload(sequence = sequence, whitelist = whitelist))

    @Test
    fun anInstalledWhitelistIsFoundByItsChainContractAndSelector() {
        val dir = dir()
        RegistryUpdate.install(signed(), keys, baked, dir).getOrThrow()

        val merged = RegistryUpdate.withInstalled(baked, dir, keys)
        val entry = assertNotNull(merged.whitelist(4242L, TEST_CONTRACT, SWAP_SELECTOR))
        assertEquals("swapExactTokensForTokens(uint256,uint256,address)", entry.signature)
        assertEquals("Swap tokens on the test route", entry.meaning)
        assertNull(merged.whitelist(4242L, TEST_CONTRACT, APPROVE_SELECTOR))
        assertNull(baked.whitelist(4242L, TEST_CONTRACT, SWAP_SELECTOR))
    }

    @Test
    fun aPackageReplacesTheWhitelistOfTheBuildAsAWhole() {
        val approvedInBuild = WhitelistEntry(
            chainId = 1L,
            contract = TEST_CONTRACT,
            selector = APPROVE_SELECTOR,
            signature = "approve(address,uint256)",
            meaning = "Build approval",
            params = listOf(WhitelistParam("spender", "address"), WhitelistParam("value", "uint256")),
            upgradeable = false,
        )
        val build = baked.withWhitelist(listOf(approvedInBuild))
        val dir = dir()

        RegistryUpdate.install(signed(), keys, build, dir).getOrThrow()

        val merged = RegistryUpdate.withInstalled(build, dir, keys)
        assertNull(merged.whitelist(1L, TEST_CONTRACT, APPROVE_SELECTOR))
        assertNotNull(merged.whitelist(4242L, TEST_CONTRACT, SWAP_SELECTOR))
    }

    @Test
    fun aPackageWithoutAWhitelistLeavesNoApprovedCallsButStillAddsItsNetwork() {
        val dir = dir()
        RegistryUpdate.install(signed(sequence = 1), keys, baked, dir).getOrThrow()

        RegistryUpdate.install(signed(sequence = 2, whitelist = null), keys, baked, dir).getOrThrow()

        val merged = RegistryUpdate.withInstalled(baked, dir, keys)
        assertTrue(merged.whitelistEntries().isEmpty())
        assertNotNull(merged.network(4242L))
    }

    @Test
    fun aPackageWithoutAWhitelistReadsAsAnEmptyOne() {
        val result = RegistryUpdate.verify(TestPackages.signed(TestPackages.payload()), keys, baked)

        assertTrue(result.getOrThrow().whitelist.isEmpty())
    }

    @Test
    fun aProxyBindingIsReadWithItsImplementation() {
        val parsed = RegistryUpdate.verify(signed(whitelist = PROXY_ENTRY), keys, baked).getOrThrow()

        assertEquals(
            ProxyBinding(implementationAddress = "0x7777777777777777777777777777777777777777"),
            parsed.whitelist.single().proxy,
        )
    }

    @Test
    fun aTamperedWhitelistEntryIsRejectedAndNothingIsStored() {
        val payload = TestPackages.payload(whitelist = TestPackages.LINEA_SWAP)
        val bytes = TestPackages.signed(payload)
        bytes[16 + payload.indexOf("Swap tokens")] = 'X'.code.toByte()
        val dir = dir()

        val result = RegistryUpdate.install(bytes, keys, baked, dir)

        assertIs<RegistryUpdateException.BadSignature>(failure(result))
        assertFalse(File(dir, RegistryUpdate.INSTALLED_FILE).exists())
    }

    @Test
    fun anEntryOnAChainNeitherBakedNorInThePackageIsRejected() {
        val onUnknownChain = TestPackages.LINEA_SWAP.replace("\"chainId\":4242", "\"chainId\":777777")

        val result = RegistryUpdate.verify(signed(whitelist = onUnknownChain), keys, baked)

        assertIs<RegistryUpdateException.InvalidEntry>(failure(result))
    }

    @Test
    fun aSelectorThatIsNotTheSignaturesIsRejected() {
        val wrongSelector = TestPackages.LINEA_SWAP.replace(SWAP_SELECTOR, APPROVE_SELECTOR)

        val result = RegistryUpdate.verify(signed(whitelist = wrongSelector), keys, baked)

        assertIs<RegistryUpdateException.InvalidEntry>(failure(result))
    }

    @Test
    fun aProxyNamingNoImplementationIsRejected() {
        val noBinding = PROXY_ENTRY.replace(
            "\"proxy\":{\"implementationAddress\":\"0x7777777777777777777777777777777777777777\",\"implementationCodeHash\":null}",
            "\"proxy\":{}",
        )

        val result = RegistryUpdate.verify(signed(whitelist = noBinding), keys, baked)

        assertIs<RegistryUpdateException.InvalidEntry>(failure(result))
    }

    @Test
    fun aMeaningOverTwoHundredFortyCharactersIsRejected() {
        val longMeaning = TestPackages.LINEA_SWAP.replace("Swap tokens on the test route", "x".repeat(241))

        val result = RegistryUpdate.verify(signed(whitelist = longMeaning), keys, baked)

        assertIs<RegistryUpdateException.InvalidEntry>(failure(result))
    }

    @Test
    fun theSameChainContractAndSelectorTwiceInOnePackageIsRejected() {
        val twice = "${TestPackages.LINEA_SWAP},${TestPackages.LINEA_SWAP}"

        val result = RegistryUpdate.verify(signed(whitelist = twice), keys, baked)

        assertIs<RegistryUpdateException.Conflict>(failure(result))
    }

    @Test
    fun anOlderPackageWithAWhitelistCannotRollBackTheCatalog() {
        val dir = dir()
        RegistryUpdate.install(signed(sequence = 5), keys, baked, dir).getOrThrow()

        val older = RegistryUpdate.install(signed(sequence = 4, whitelist = null), keys, baked, dir)

        assertIs<RegistryUpdateException.NotNewer>(failure(older))
        assertNotNull(RegistryUpdate.withInstalled(baked, dir, keys).whitelist(4242L, TEST_CONTRACT, SWAP_SELECTOR))
    }
}
