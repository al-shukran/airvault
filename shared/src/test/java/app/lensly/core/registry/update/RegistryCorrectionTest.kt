package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.MemoryKeystore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

/** A signed package corrects a network or token that the build bakes in, instead of being refused as a whole. */
class RegistryCorrectionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")

    private fun ethereum(name: String = "Ethereum Renamed", kind: String = "LAYER_1", parent: String = "null", testnet: Boolean = false) =
        """{"chainId":1,"explorerName":null,"feeModel":"L1","featured":false,"kind":"$kind","name":"$name","nativeDecimals":18,"nativeSymbol":"ETH","parentChainId":$parent,"testnet":$testnet}"""

    private fun usdc(decimals: Int) =
        """{"chainId":1,"contract":"$USDC_ETHEREUM","decimals":$decimals,"name":"USD Coin","symbol":"USDC"}"""

    private fun install(payload: String): Registry {
        RegistryUpdate.install(TestPackages.signed(payload), keys, baked, dir, keystore).getOrThrow()
        return RegistryUpdate.withInstalled(baked, dir, keys, keystore)
    }

    @Test
    fun aPackageCorrectsABakedNetwork() {
        val before = assertNotNull(baked.network(1L))

        val merged = install(TestPackages.payload(networks = ethereum(), tokens = ""))

        assertEquals("Ethereum Renamed", assertNotNull(merged.network(1L)).name)
        assertEquals(baked.networks().size, merged.networks().size, "a correction adds no network")
        assertTrue(before.name != "Ethereum Renamed")
    }

    @Test
    fun aPackageCorrectsABakedToken() {
        val before = assertNotNull(baked.token(1L, USDC_ETHEREUM))

        val merged = install(TestPackages.payload(networks = "", tokens = usdc(decimals = 7)))

        assertEquals(7, assertNotNull(merged.token(1L, USDC_ETHEREUM)).decimals)
        assertEquals(baked.tokens().size, merged.tokens().size, "a correction adds no token")
        assertTrue(before.decimals != 7)
    }

    @Test
    fun aCorrectionThatOmitsTheIconKeepsTheBakedIcon() {
        val network = assertNotNull(baked.network(1L))
        val token = assertNotNull(baked.token(1L, USDC_ETHEREUM))
        assertNotNull(network.icon)
        assertNotNull(token.icon)

        val merged = install(TestPackages.payload(networks = ethereum(), tokens = usdc(decimals = 7)))

        assertEquals(network.icon, assertNotNull(merged.network(1L)).icon)
        assertEquals(token.icon, assertNotNull(merged.token(1L, USDC_ETHEREUM)).icon)
    }

    @Test
    fun aCorrectionDoesNotTouchTheOtherBakedEntries() {
        val merged = install(TestPackages.payload(networks = ethereum(), tokens = ""))

        for (network in baked.networks().filter { it.chainId != 1L }) assertEquals(network, merged.network(network.chainId))
        assertEquals(baked.tokens(), merged.tokens())
    }

    @Test
    fun aCorrectionThatMakesALayerOneARollupIsRefusedWhileBakedRollupsSettleToIt() {
        // Optimism (10) is a baked Layer 2 that settles to Ethereum: it would be left settling to a Layer 2.
        val rollup = ethereum(kind = "LAYER_2", parent = "56")

        val refused = assertNotNull(RegistryUpdate.verify(TestPackages.signed(TestPackages.payload(networks = rollup, tokens = "")), keys, baked).exceptionOrNull())

        assertIs<RegistryUpdateException.InvalidEntry>(refused)
        assertTrue("chain 10 settles to chain 1, which is not a Layer 1" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun aCorrectionCannotTurnABakedNetworkIntoATestnet() {
        val refused = RegistryUpdate.verify(
            TestPackages.signed(TestPackages.payload(networks = ethereum(testnet = true), tokens = "")),
            keys,
            baked,
        ).exceptionOrNull()

        assertIs<RegistryUpdateException.InvalidEntry>(refused)
    }

    @Test
    fun aCorrectedEntryStillCannotAppearTwiceInOnePackage() {
        val twice = "${ethereum()},${ethereum("Again")}"

        val refused = RegistryUpdate.verify(TestPackages.signed(TestPackages.payload(networks = twice, tokens = "")), keys, baked)

        assertIs<RegistryUpdateException.Conflict>(refused.exceptionOrNull())
    }

    @Test
    fun registryPackageEntriesReplaceTheEntryWithTheSameKeyAndAddTheOthers() {
        val renamed = assertNotNull(baked.network(1L)).copy(name = "Renamed")
        val added = assertNotNull(baked.network(1L)).copy(chainId = 99_999L, name = "Added")

        val merged = baked.withPackageEntries(listOf(renamed, added), emptyList())

        assertEquals("Renamed", assertNotNull(merged.network(1L)).name)
        assertEquals("Added", assertNotNull(merged.network(99_999L)).name)
        assertEquals(baked.networks().size + 1, merged.networks().size)
    }
}
