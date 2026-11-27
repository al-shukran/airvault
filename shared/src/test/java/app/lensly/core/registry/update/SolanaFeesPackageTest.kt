package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.SolanaFees
import app.lensly.core.vault.MemoryKeystore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val FEES =
    """{"builtinUnitsPerInstruction":4000,"defaultUnitsPerInstruction":300000,"lamportsPerSignature":7000,"maxUnits":1500000}"""

/** The Solana fee constants a review computes its Max Fee with are carried by the signed package. */
class SolanaFeesPackageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")

    private fun view(payload: String): Registry {
        RegistryUpdate.install(TestPackages.signed(payload), keys, baked, dir, keystore).getOrThrow()
        return RegistryUpdate.withInstalled(baked, dir, keys, keystore)
    }

    @Test
    fun theBuildShipsTheBuiltInConstants() {
        assertEquals(SolanaFees.DEFAULT, baked.solanaFees)
    }

    @Test
    fun anInstalledPackageReplacesTheConstants() {
        val merged = view(TestPackages.payload(minSchemaVersion = 6, solanaFees = FEES))

        assertEquals(SolanaFees(7_000, 300_000, 4_000, 1_500_000), merged.solanaFees)
    }

    @Test
    fun aPackageWithoutConstantsLeavesTheBuiltInOnes() {
        assertEquals(SolanaFees.DEFAULT, view(TestPackages.payload(minSchemaVersion = 6)).solanaFees)
    }

    @Test
    fun constantsNeedSchemaSix() {
        val refused = RegistryUpdate.verify(
            TestPackages.signed(TestPackages.payload(minSchemaVersion = 5, solanaFees = FEES)),
            keys,
            baked,
        ).exceptionOrNull()

        assertIs<RegistryUpdateException.Malformed>(refused)
    }

    @Test
    fun constantsOutsideTheirRangeAreRefused() {
        val cases = mapOf(
            "no signature fee" to FEES.replace("\"lamportsPerSignature\":7000", "\"lamportsPerSignature\":0"),
            "negative signature fee" to FEES.replace("\"lamportsPerSignature\":7000", "\"lamportsPerSignature\":-1"),
            "no default units" to FEES.replace("\"defaultUnitsPerInstruction\":300000", "\"defaultUnitsPerInstruction\":0"),
            "no builtin units" to FEES.replace("\"builtinUnitsPerInstruction\":4000", "\"builtinUnitsPerInstruction\":0"),
            "a cap above 32 bits" to FEES.replace("\"maxUnits\":1500000", "\"maxUnits\":4294967296"),
            "a fee that wraps the base fee" to FEES.replace("\"lamportsPerSignature\":7000", "\"lamportsPerSignature\":6148914691236517206"),
            "a fee below the build's" to FEES.replace("\"lamportsPerSignature\":7000", "\"lamportsPerSignature\":4999"),
            "units below the build's" to FEES.replace("\"maxUnits\":1500000", "\"maxUnits\":1399999"),
            "a cap above a thousand times the build's" to FEES.replace("\"maxUnits\":1500000", "\"maxUnits\":1400000001"),
        )
        for ((label, fees) in cases) {
            val refused = RegistryUpdate.verify(
                TestPackages.signed(TestPackages.payload(minSchemaVersion = 6, solanaFees = fees)),
                keys,
                baked,
            ).exceptionOrNull()

            assertIs<RegistryUpdateException.InvalidEntry>(refused, label)
        }
    }
}
