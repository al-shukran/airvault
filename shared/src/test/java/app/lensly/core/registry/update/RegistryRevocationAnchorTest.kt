package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.MemoryKeystore
import java.io.File
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A revocation in force is anchored in the Keystore, which file access cannot delete, so deleting the seal and the installed package
 * does not make a revoked key's package install again. The first key is the recovery key, the second the key that leaked, and the
 * third an unrelated release key.
 */
class RegistryRevocationAnchorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED_BOTH + TrustedKey(TestPackages.THIRD_PUBLIC_BYTES)
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")
    private val leaked = TestPackages.OTHER_KEY_ID

    private fun signed(sequence: Long, seed: ByteArray, revoked: List<String> = emptyList()) =
        TestPackages.signed(TestPackages.payload(sequence = sequence, minSchemaVersion = 6, revokedKeys = revoked), seed)

    private fun byRecovery(sequence: Long, revoked: List<String> = listOf(leaked)) = signed(sequence, TestPackages.SEED, revoked)

    private fun byLeaked(sequence: Long) = signed(sequence, TestPackages.OTHER_SEED)

    private fun install(bytes: ByteArray, store: KeystoreBackend = keystore) = RegistryUpdate.install(bytes, keys, baked, dir, store)

    private fun deleteSeal() = assertTrue(File(dir, RegistrySeal.FILE).delete())

    /** A release-signed restatement, so only the seal holds the revocation. */
    private fun restatedByRelease() {
        install(byRecovery(1)).getOrThrow()
        install(signed(2, TestPackages.THIRD_SEED, listOf(leaked))).getOrThrow()
    }

    @Test
    fun aDeletedSealDoesNotDropARevocationThatAReleaseSignedFileRestated() {
        restatedByRelease()
        deleteSeal()

        assertEquals(leaked, assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(3)).exceptionOrNull()).keyId)
    }

    @Test
    fun aDeletedSealDoesNotLetARevokedKeyInstallAtTheFloorEither() {
        restatedByRelease()
        deleteSeal()

        assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(2)).exceptionOrNull())
    }

    @Test
    fun aDeletedSealAndInstalledFileDoNotDropARecoverySignedRevocation() {
        install(byRecovery(1)).getOrThrow()
        deleteSeal()
        assertTrue(File(dir, RegistryUpdate.INSTALLED_FILE).delete())

        assertEquals(leaked, assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(2)).exceptionOrNull()).keyId)
    }

    @Test
    fun anUnrelatedActiveKeyStillInstallsAfterTheSealIsDeleted() {
        restatedByRelease()
        deleteSeal()

        assertTrue(install(signed(3, TestPackages.THIRD_SEED, listOf(leaked))).isSuccess)
        // A package that does not restate the revocation is still refused: it stands whatever happens to the files.
        assertIs<RegistryUpdateException.Conflict>(install(signed(4, TestPackages.THIRD_SEED)).exceptionOrNull())
    }

    @Test
    fun anInstallAnchorsTheRevocationsItMakesStand() {
        assertEquals(emptyList(), keystore.registryRevokedKeyIds())

        install(byRecovery(1, listOf(leaked, "0123456789abcdef"))).getOrThrow()

        assertEquals(setOf(leaked, "0123456789abcdef"), keystore.registryRevokedKeyIds().toSet())
    }

    @Test
    fun aRefusedPackageAnchorsNothing() {
        assertIs<RegistryUpdateException.InvalidEntry>(install(signed(1, TestPackages.THIRD_SEED, listOf(leaked))).exceptionOrNull())

        assertEquals(emptyList(), keystore.registryRevokedKeyIds())
    }

    @Test
    fun theFirstLoadOfAVerifiedSealAnchorsItsRevocations() {
        install(byRecovery(1)).getOrThrow()
        // A store sealed before revocations were anchored: the seal holds the revocation, the Keystore does not.
        keystore.forgetRegistryRevocations()
        assertEquals(emptyList(), keystore.registryRevokedKeyIds())

        assertFalse(RegistryCatalog(baked, dir, keys, keystore).current().installedUpdateUnverified)

        assertEquals(listOf(leaked), keystore.registryRevokedKeyIds())
        deleteSeal()
        assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(2)).exceptionOrNull())
    }

    @Test
    fun aKeystoreThatCannotListTheAnchoredRevocationsRefusesInstallsAndNeverTakesThemForNone() {
        install(byRecovery(1)).getOrThrow()
        deleteSeal()
        val broken = object : KeystoreBackend by keystore {
            override fun registryRevokedKeyIds(): List<String> = throw GeneralSecurityException("The Keystore is unavailable.")
        }

        assertTrue(install(byLeaked(2), broken).isFailure)
        assertTrue(install(signed(2, TestPackages.THIRD_SEED, listOf(leaked)), broken).isFailure)

        // The unverified seal and the unknown revocations leave the built-in catalog in use, flagged, and nothing is thrown.
        assertTrue(RegistryUpdate.withInstalled(baked, dir, keys, broken).installedUpdateUnverified)
        assertEquals(null, RegistryUpdate.installedPackage(baked, dir, keys, broken))
    }
}
