package app.lensly.ui.home

import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.update.RegistryPackage
import app.lensly.core.registry.update.RegistryUpdateException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class RegistryImportStateTest {
    @Test
    fun tamperedAndWrongKeyPackagesShareTheSignatureReason() {
        assertTrue(refusalReason(RegistryUpdateException.BadSignature()).startsWith("Signature check failed"))
    }

    @Test
    fun anOlderSequenceNamesBothSequences() {
        assertEquals(
            "Sequence 1 is not newer than the installed sequence 2. An older package cannot roll the catalog back.",
            refusalReason(RegistryUpdateException.NotNewer(installed = 2, offered = 1)),
        )
    }

    @Test
    fun aConflictShowsTheConflictMessage() {
        assertEquals(
            "Conflict: chain 1 is already in the baked catalog.",
            refusalReason(RegistryUpdateException.Conflict("chain 1 is already in the baked catalog")),
        )
    }

    @Test
    fun aFileThatCannotBeReadGetsAGenericReason() {
        assertEquals("The package could not be read or stored.", refusalReason(IOException("gone")))
    }

    @Test
    fun anAcceptedPackageNamesWhatItAdded() {
        val pkg = RegistryPackage(
            sequence = 1,
            createdAt = "2026-11-10",
            networks = listOf(NetworkEntry(59144L, "Linea", "ETH", 18, FeeModel.L2_ROLLUP, "Lineascan", false)),
            tokens = listOf(TokenEntry(59144L, "0x176211869ca2b568f2a7d4ee941e073a821ee1ff", "USDC", "USD Coin", 6)),
        )

        assertEquals(
            ImportState.Installed(
                sequence = 1,
                networks = listOf("Linea · chain 59144"),
                tokens = listOf("USDC · chain 59144"),
            ),
            importedState(pkg),
        )
    }
}
