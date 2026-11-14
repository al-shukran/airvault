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
    fun anAlteredPackageFailsTheSignatureCheck() {
        assertTrue(refusalReason(RegistryUpdateException.BadSignature()).startsWith("Signature check failed"))
    }

    @Test
    fun aPackageFromAnUntrustedOrRevokedKeyNamesTheKey() {
        assertTrue(refusalReason(RegistryUpdateException.UnknownKey("8e550bd7fc1c9e21")).contains("8e550bd7fc1c9e21"))
        assertTrue(refusalReason(RegistryUpdateException.RevokedKey("8e550bd7fc1c9e21")).startsWith("Signed with a revoked key"))
    }

    @Test
    fun anUnsupportedFormatNamesTheVersionThisAppReads() {
        assertEquals(
            "Registry format version 1 is not supported. This app reads format version 2.",
            refusalReason(RegistryUpdateException.UnsupportedFormat(version = 1, supported = 2)),
        )
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
    fun anAcceptedPackageListsWhatItAdded() {
        val linea = NetworkEntry(59144L, "Linea", "ETH", 18, FeeModel.L2_ROLLUP, "Lineascan", false)
        val usdc = TokenEntry(59144L, "0x176211869ca2b568f2a7d4ee941e073a821ee1ff", "USDC", "USD Coin", 6)
        val pkg = RegistryPackage(sequence = 1, createdAt = "2026-11-10", networks = listOf(linea), tokens = listOf(usdc))

        assertEquals(
            ImportState.Installed(sequence = 1, networks = listOf(linea), tokens = listOf(usdc)),
            importedState(pkg),
        )
    }
}
