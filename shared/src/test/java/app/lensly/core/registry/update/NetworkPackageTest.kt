package app.lensly.core.registry.update

import app.lensly.ui.shippedRegistry
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A Layer 2 network on chain 4242 that settles to [parent], as the test payload writes it. */
private fun layerTwoOn(parent: Long): String =
    TestPackages.LINEA_NETWORK.replace("\"parentChainId\":1", "\"parentChainId\":$parent")

/** The refusals of a package whose networks name a parent chain that is not a Layer 1, or that needs an older schema. */
class NetworkPackageTest {
    private val baked = shippedRegistry()

    private fun verify(payload: String): Result<RegistryPackage> =
        RegistryUpdate.verify(TestPackages.signed(payload), TestPackages.TRUSTED, baked)

    private fun failure(result: Result<*>): Throwable = assertNotNull(result.exceptionOrNull())

    @Test
    fun aPackageWithNetworksNeedsSchemaFour() {
        assertIs<RegistryUpdateException.Malformed>(failure(verify(TestPackages.payload(minSchemaVersion = 3))))
    }

    @Test
    fun aLayerTwoNamingItselfAsParentIsRefused() {
        assertIs<RegistryUpdateException.InvalidEntry>(failure(verify(TestPackages.payload(networks = layerTwoOn(4242)))))
    }

    @Test
    fun aLayerTwoNamingAnotherPackagedLayerTwoAsParentIsRefused() {
        val otherLayerTwo = TestPackages.LINEA_NETWORK.replace("\"chainId\":4242", "\"chainId\":5")
            .replace("\"parentChainId\":1", "\"parentChainId\":4242")

        assertIs<RegistryUpdateException.InvalidEntry>(
            failure(verify(TestPackages.payload(networks = "${layerTwoOn(5)},$otherLayerTwo"))),
        )
    }

    @Test
    fun aLayerTwoNamingABakedLayerTwoAsParentIsRefused() {
        // Optimism (10) is a Layer 2 in the shipped catalog.
        assertIs<RegistryUpdateException.InvalidEntry>(failure(verify(TestPackages.payload(networks = layerTwoOn(10)))))
    }

    @Test
    fun aLayerTwoNamingABakedLayerOneAsParentStillVerifies() {
        assertTrue(verify(TestPackages.payload()).isSuccess)
    }
}
