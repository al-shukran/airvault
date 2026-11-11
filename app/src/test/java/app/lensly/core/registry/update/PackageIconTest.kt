package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/** The optional `icon` field in signed registry packages. Packages signed before logos existed must still verify. */
class PackageIconTest {
    private val baked: Registry = RegistryLoader.load { path ->
        File("src/main/assets", path).takeIf { it.isFile }?.readText()
    }

    private fun verify(payload: String): Result<RegistryPackage> =
        RegistryUpdate.verify(TestPackages.signed(payload), listOf(TestPackages.PUBLIC_KEY), baked)

    @Test
    fun aPackageWithoutIconFieldsStillVerifies() {
        val pkg = verify(TestPackages.payload()).getOrThrow()

        assertNull(pkg.networks.single().icon)
        assertNull(pkg.tokens.single().icon)
    }

    @Test
    fun aPackageCanNameTheLogosOfItsNetworkAndToken() {
        val pkg = verify(
            TestPackages.payload(networks = LINEA_WITH_ICON, tokens = LINEA_USDC_WITH_ICON),
        ).getOrThrow()

        assertEquals("chain_ethereum", pkg.networks.single().icon)
        assertEquals("token_usdc", pkg.tokens.single().icon)
    }

    @Test
    fun aLogoNameWithControlCharactersIsRefused() {
        val failure = verify(TestPackages.payload(tokens = LINEA_USDC_WITH_CONTROL_ICON)).exceptionOrNull()

        assertIs<RegistryUpdateException.InvalidEntry>(failure)
    }

    private companion object {
        const val LINEA_WITH_ICON =
            """{"chainId":4242,"explorerName":"Lineascan","feeModel":"L2_ROLLUP","icon":"chain_ethereum",""" +
                """"name":"Linea","nativeDecimals":18,"nativeSymbol":"ETH","testnet":false}"""
        const val LINEA_USDC_WITH_ICON =
            """{"chainId":4242,"contract":"0x176211869ca2b568f2a7d4ee941e073a821ee1ff","decimals":6,""" +
                """"icon":"token_usdc","name":"USDC","symbol":"USDC"}"""
        const val LINEA_USDC_WITH_CONTROL_ICON =
            """{"chainId":4242,"contract":"0x176211869ca2b568f2a7d4ee941e073a821ee1ff","decimals":6,""" +
                """"icon":"token_\u0007usdc","name":"USDC","symbol":"USDC"}"""
    }
}
