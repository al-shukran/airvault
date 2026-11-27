package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.MemoryKeystore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A signed package revokes signing keys in the field. Two test keys are trusted: the first signs the revoking package, and the
 * second is the key that leaked. The build's own key list stays unchanged throughout.
 */
class RegistryKeyRevocationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED_BOTH
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")

    private val leaked = TestPackages.OTHER_KEY_ID

    private fun signedByFirst(sequence: Long, revokedKeys: List<String> = emptyList(), whitelist: String? = null) = TestPackages.signed(
        TestPackages.payload(sequence = sequence, minSchemaVersion = 6, whitelist = whitelist, revokedKeys = revokedKeys),
    )

    private fun signedByLeaked(sequence: Long, whitelist: String? = null) = TestPackages.signed(
        TestPackages.payload(sequence = sequence, minSchemaVersion = 6, whitelist = whitelist),
        TestPackages.OTHER_SEED,
    )

    private fun install(bytes: ByteArray) = RegistryUpdate.install(bytes, keys, baked, dir, keystore)

    @Test
    fun theLeakedKeyIsAcceptedUntilAPackageRevokesIt() {
        assertTrue(install(signedByLeaked(1)).isSuccess)
    }

    @Test
    fun aRevokedKeysPackageIsRefusedAfterAPackageRevokesTheKey() {
        install(signedByFirst(1, revokedKeys = listOf(leaked))).getOrThrow()

        val refused = install(signedByLeaked(2)).exceptionOrNull()

        assertEquals(leaked, assertIs<RegistryUpdateException.RevokedKey>(refused).keyId)
    }

    @Test
    fun theRevocationAppliesToTheNextAppStartToo() {
        install(signedByFirst(1, revokedKeys = listOf(leaked))).getOrThrow()

        // A fresh read of the store, as after a restart: the installed package still verifies, and the leaked key is still refused.
        assertEquals(listOf(leaked), assertNotNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore)).revokedKeys)
        assertIs<RegistryUpdateException.RevokedKey>(install(signedByLeaked(5)).exceptionOrNull())
    }

    @Test
    fun theRevocationStaysInForceWhenTheSealCannotBeVerified() {
        install(signedByFirst(1, revokedKeys = listOf(leaked))).getOrThrow()
        // A lost or replaced seal leaves the store unverified: the catalog falls back to the built-in one, but the installed package
        // still verifies against a trusted key, so the leaked key stays refused.
        File(dir, RegistrySeal.FILE).delete()

        assertIs<RegistryUpdateException.RevokedKey>(install(signedByLeaked(2)).exceptionOrNull())
    }

    @Test
    fun aLaterPackageMustKeepRevokingTheSameKeys() {
        install(signedByFirst(1, revokedKeys = listOf(leaked))).getOrThrow()

        assertIs<RegistryUpdateException.Conflict>(install(signedByFirst(2)).exceptionOrNull())
        assertTrue(install(signedByFirst(2, revokedKeys = listOf(leaked))).isSuccess)
    }

    @Test
    fun aReleaseKeyCannotRevokeKeys() {
        val revoking = TestPackages.signed(
            TestPackages.payload(minSchemaVersion = 6, revokedKeys = listOf(TestPackages.KEY_ID)),
            TestPackages.OTHER_SEED,
        )

        assertIs<RegistryUpdateException.InvalidEntry>(install(revoking).exceptionOrNull())
    }

    @Test
    fun aRecoveryPackageThatRevokesTheInstalledSignerIsAcceptedWhateverItsSequence() {
        // The leaked key installs a package with a huge sequence, which would otherwise outrank every honest package.
        install(signedByLeaked(1_000_000)).getOrThrow()

        install(signedByFirst(5, revokedKeys = listOf(leaked))).getOrThrow()

        assertEquals(5L, assertNotNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore)).sequence)
        assertIs<RegistryUpdateException.RevokedKey>(install(signedByLeaked(2_000_000)).exceptionOrNull())
        assertTrue(install(signedByFirst(6, revokedKeys = listOf(leaked))).isSuccess)
        assertIs<RegistryUpdateException.NotNewer>(install(signedByFirst(6, revokedKeys = listOf(leaked))).exceptionOrNull())
    }

    @Test
    fun aRevokingPackageThatDoesNotRevokeTheInstalledSignerStillNeedsAHigherSequence() {
        install(signedByFirst(10)).getOrThrow()

        val refused = install(signedByFirst(5, revokedKeys = listOf(leaked))).exceptionOrNull()

        assertIs<RegistryUpdateException.NotNewer>(refused)
    }

    @Test
    fun aPackageCannotRevokeTheKeyThatSignedIt() {
        val refused = install(signedByFirst(1, revokedKeys = listOf(TestPackages.KEY_ID))).exceptionOrNull()

        assertIs<RegistryUpdateException.InvalidEntry>(refused)
    }

    @Test
    fun revokedKeysNeedSchemaSix() {
        val payload = TestPackages.payload(minSchemaVersion = 5, revokedKeys = listOf(leaked))

        val refused = RegistryUpdate.verify(TestPackages.signed(payload), keys, baked).exceptionOrNull()

        assertIs<RegistryUpdateException.Malformed>(refused)
    }

    @Test
    fun aMalformedRevokedKeyIDIsRefused() {
        for (bad in listOf("zz", leaked.uppercase(), leaked.take(8), "")) {
            val payload = TestPackages.payload(minSchemaVersion = 6, revokedKeys = listOf(bad))

            val refused = RegistryUpdate.verify(TestPackages.signed(payload), keys, baked).exceptionOrNull()

            assertIs<RegistryUpdateException.InvalidEntry>(refused, "revoked key '$bad'")
        }
        val twice = TestPackages.payload(minSchemaVersion = 6, revokedKeys = listOf(leaked, leaked))
        assertIs<RegistryUpdateException.InvalidEntry>(
            RegistryUpdate.verify(TestPackages.signed(twice), keys, baked).exceptionOrNull(),
        )
    }

    @Test
    fun aListSignedByTheRevokedKeyIsNotKeptBehindThePackageThatRevokesIt() {
        install(signedByLeaked(1, whitelist = TestPackages.LINEA_SWAP)).getOrThrow()

        // The revoking package does not restate the whitelist that the leaked key signed, so it is refused rather than dropping it silently.
        assertIs<RegistryUpdateException.Conflict>(install(signedByFirst(2, revokedKeys = listOf(leaked))).exceptionOrNull())
        // Restating the whitelist under the new key installs, and the catalog verifies.
        install(signedByFirst(2, revokedKeys = listOf(leaked), whitelist = TestPackages.LINEA_SWAP)).getOrThrow()
        val view = RegistryUpdate.withInstalled(baked, dir, keys, keystore)
        assertFalse(view.installedUpdateUnverified)
        assertNotNull(view.whitelist(4242L, "0x5555555555555555555555555555555555555555", "0x6e17e9f6"))
    }
}
