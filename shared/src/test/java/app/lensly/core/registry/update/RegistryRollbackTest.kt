package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.MemoryKeystore
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

/** The name of the seal file. Spelled out so the attack tests below read the same against any build of the registry store. */
private const val SEAL_FILE = "registry-seal"

/**
 * Attacks on the stored registry with file access to the app's private storage: replacing or deleting the files, or putting an
 * older copy back. Each must leave the catalog on the newest accepted package or on the built-in one, and must keep refusing older
 * packages.
 */
class RegistryRollbackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir = folder.root.resolve("registry")

    private fun signedAt(sequence: Long, whitelist: String? = null): ByteArray =
        TestPackages.signed(TestPackages.payload(sequence = sequence, whitelist = whitelist))

    private fun install(bytes: ByteArray): Result<RegistryPackage> = RegistryUpdate.install(bytes, keys, baked, dir, keystore)

    private fun view(): Registry = RegistryUpdate.withInstalled(baked, dir, keys, keystore)

    private fun whitelistMeanings(): List<String> = view().whitelistEntries().map { it.meaning }

    private fun swap(meaning: String) = TestPackages.LINEA_SWAP.replace("Swap tokens on the test route", meaning)

    /** Every file of the store, so a test can put the whole thing back as it was. */
    private fun snapshot(): Map<String, ByteArray> = dir.listFiles().orEmpty().associate { it.name to it.readBytes() }

    @Test
    fun anOlderSignedWhitelistPackageSwappedInIsNotUsed() {
        install(signedAt(3, whitelist = swap("Older swap"))).getOrThrow()
        val older = File(dir, RegistryUpdate.WHITELIST_FILE).readBytes()
        install(signedAt(5, whitelist = swap("Newer swap"))).getOrThrow()
        // The package that carried the whitelist last is 5, and 6 does not restate it.
        install(signedAt(6)).getOrThrow()
        assertEquals(listOf("Newer swap"), whitelistMeanings())

        File(dir, RegistryUpdate.WHITELIST_FILE).writeBytes(older)

        assertFalse("Older swap" in whitelistMeanings())
    }

    @Test
    fun deletingTheStoreAndInstallingAnOlderPackageIsRefused() {
        install(signedAt(5)).getOrThrow()

        dir.deleteRecursively()

        assertEquals(5L, assertIs<RegistryUpdateException.NotNewer>(install(signedAt(3)).exceptionOrNull()).installed)
        assertIs<RegistryUpdateException.NotNewer>(install(signedAt(5)).exceptionOrNull())
        assertFalse(File(dir, RegistryUpdate.INSTALLED_FILE).exists())
        assertTrue(install(signedAt(6)).isSuccess)
    }

    @Test
    fun anOlderPackageSwappedInWithTheRecordDeletedIsNotUsed() {
        install(signedAt(5)).getOrThrow()

        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(signedAt(3))
        File(dir, SEAL_FILE).delete()

        assertNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore))
        assertTrue(view().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(install(signedAt(4)).exceptionOrNull())
    }

    @Test
    fun anOlderStoreCopiedBackWholeIsNotUsed() {
        install(signedAt(3)).getOrThrow()
        val older = snapshot()
        install(signedAt(5)).getOrThrow()

        dir.deleteRecursively()
        dir.mkdirs()
        older.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }

        assertNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore))
        assertTrue(view().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(install(signedAt(4)).exceptionOrNull())
    }

    @Test
    fun theStoredPackageStaysInUseWhenNothingWasTouched() {
        install(signedAt(5, whitelist = swap("Swap"))).getOrThrow()
        install(signedAt(6)).getOrThrow()

        assertNotNull(view().network(4242L))
        assertFalse(view().installedUpdateUnverified)
        assertEquals(listOf("Swap"), whitelistMeanings())
    }
}
