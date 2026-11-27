package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.MemoryKeystore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Keystore that loses its keys while the registry files survive (an OEM or OTA reset) takes the seal's key and the anchored revocations
 * with it, so the revocations in force are unknown. Then the built-in catalog is used and only a recovery-signed package installs, which
 * restates them. The first key is the recovery key, the second the key that leaked, and the third an unrelated release key.
 */
class RegistryKeystoreLossTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED_BOTH + TrustedKey(TestPackages.THIRD_PUBLIC_BYTES)
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")
    private val leaked = TestPackages.OTHER_KEY_ID
    private val unknownSeed = ByteArray(32) { (it * 11 + 17).toByte() }

    private fun signed(sequence: Long, seed: ByteArray, revoked: List<String> = emptyList()) =
        TestPackages.signed(TestPackages.payload(sequence = sequence, minSchemaVersion = 6, revokedKeys = revoked), seed)

    private fun byRecovery(sequence: Long, revoked: List<String> = listOf(leaked)) = signed(sequence, TestPackages.SEED, revoked)

    private fun byLeaked(sequence: Long) = signed(sequence, TestPackages.OTHER_SEED)

    private fun byRelease(sequence: Long, revoked: List<String> = listOf(leaked)) = signed(sequence, TestPackages.THIRD_SEED, revoked)

    private fun install(bytes: ByteArray) = RegistryUpdate.install(bytes, keys, baked, dir, keystore)

    /** The recovery key revoked the leaked key, and a release-signed file restated it, so only that file and the Keystore hold it. */
    private fun restatedByRelease() {
        install(byRecovery(1)).getOrThrow()
        install(byRelease(2)).getOrThrow()
    }

    private fun loseKeystore() {
        keystore.deleteRegistryMacKeysExcept(null)
        keystore.forgetRegistryRevocations()
    }

    private fun storeBytes() = dir.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

    @Test
    fun aLostKeystoreDoesNotLetAKeyThatAReleaseSignedFileRevokedInstall() {
        restatedByRelease()
        loseKeystore()
        val before = storeBytes()

        assertIs<RegistryUpdateException.KeystoreReset>(install(byLeaked(3)).exceptionOrNull())

        assertEquals(before, storeBytes())
        assertEquals(emptyList(), keystore.registryRevokedKeyIds())
        assertTrue(RegistryUpdate.withInstalled(baked, dir, keys, keystore).installedUpdateUnverified)
    }

    @Test
    fun aLostKeystoreRefusesEveryPackageButARecoveryOne() {
        restatedByRelease()
        loseKeystore()

        // A key the build trusts but does not hold as recovery, even one that no file revoked, and a key the build does not trust.
        assertIs<RegistryUpdateException.KeystoreReset>(install(byRelease(3)).exceptionOrNull())
        assertIs<RegistryUpdateException.UnknownKey>(install(signed(3, unknownSeed)).exceptionOrNull())
        assertNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore))
    }

    @Test
    fun aRecoveryPackageThatRestatesTheRevocationsRestoresNormalOperationAndTheAnchors() {
        restatedByRelease()
        loseKeystore()

        assertEquals(3L, install(byRecovery(3)).getOrThrow().sequence)

        assertEquals(listOf(leaked), keystore.registryRevokedKeyIds())
        assertEquals(listOf(3L), keystore.registryMacKeySeqs())
        assertFalse(RegistryUpdate.withInstalled(baked, dir, keys, keystore).installedUpdateUnverified)
        assertEquals(3L, RegistryUpdate.installedPackage(baked, dir, keys, keystore)?.sequence)
        // The revocation stands again, and a release key installs as before.
        assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(4)).exceptionOrNull())
        assertTrue(install(byRelease(4)).isSuccess)
    }

    @Test
    fun aLeakedReleaseKeyCannotLockOutTheOtherKeysByPlantingAFile() {
        restatedByRelease()
        loseKeystore()
        // The leaked key signs a file with a huge sequence that revokes the recovery key and the other release key.
        val planted = signed(1_000_000, TestPackages.OTHER_SEED, listOf(TrustedKey(TestPackages.PUBLIC_BYTES).id, TestPackages.THIRD_KEY_ID))
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(planted)

        assertIs<RegistryUpdateException.KeystoreReset>(install(byLeaked(1_000_001)).exceptionOrNull())
        // The huge sequence pins the floor against a recovery package that does not revoke the planting key, and only against that.
        assertIs<RegistryUpdateException.NotNewer>(install(byRecovery(3, listOf(TestPackages.THIRD_KEY_ID))).exceptionOrNull())
        assertEquals(3L, install(byRecovery(3)).getOrThrow().sequence)
        assertTrue(install(byRelease(4)).isSuccess)
    }

    @Test
    fun aHugeSequenceFileOfAReleaseKeyIsEscapedByARecoveryPackageThatRevokesThatKey() {
        install(byRecovery(1, emptyList())).getOrThrow()
        install(byRelease(1_000_000, emptyList())).getOrThrow()
        loseKeystore()

        assertIs<RegistryUpdateException.NotNewer>(install(byRecovery(2, emptyList())).exceptionOrNull())
        assertEquals(2L, install(byRecovery(2, listOf(TestPackages.THIRD_KEY_ID))).getOrThrow().sequence)
        assertEquals(listOf(TestPackages.THIRD_KEY_ID), keystore.registryRevokedKeyIds())
    }

    @Test
    fun anOldRecoveryPackageCannotBeInstalledAgainToDropARevocation() {
        install(byRecovery(1, emptyList())).getOrThrow()
        install(byRecovery(2)).getOrThrow()
        install(byRelease(3)).getOrThrow()
        loseKeystore()

        assertIs<RegistryUpdateException.NotNewer>(install(byRecovery(1, emptyList())).exceptionOrNull())
        assertIs<RegistryUpdateException.KeystoreReset>(install(byLeaked(4)).exceptionOrNull())
        assertEquals(emptyList(), keystore.registryRevokedKeyIds())
    }

    @Test
    fun aRecoveryPackageThatOmitsTheRevocationsOfAnOlderRecoveryFileIsRefused() {
        install(byRecovery(1)).getOrThrow()
        loseKeystore()

        assertIs<RegistryUpdateException.Conflict>(install(byRecovery(2, emptyList())).exceptionOrNull())
    }

    @Test
    fun aFailedRecoveryInstallLeavesTheStoreLost() {
        restatedByRelease()
        loseKeystore()
        val fullDisk: (File, ByteArray) -> Unit = { _, _ -> throw java.io.IOException("No space left on device") }

        assertTrue(RegistryUpdate.install(byRecovery(3), keys, baked, dir, keystore, fullDisk).isFailure)

        assertEquals(RegistrySeal.Reading.KeyLost, RegistrySeal.read(dir, keystore))
        assertIs<RegistryUpdateException.KeystoreReset>(install(byRelease(3)).exceptionOrNull())
        assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(3)).exceptionOrNull())
        assertEquals(3L, install(byRecovery(3)).getOrThrow().sequence)
    }

    @Test
    fun aDeletedSealAfterTheLossStillKeepsTheRevocationTheInstalledFileRestates() {
        restatedByRelease()
        loseKeystore()
        assertTrue(File(dir, RegistrySeal.FILE).delete())

        assertIs<RegistryUpdateException.RevokedKey>(install(byLeaked(3)).exceptionOrNull())
    }

    @Test
    fun aRecoverySignedFileStillNamesTheFloorAfterAKeystoreLoss() {
        install(byRecovery(5)).getOrThrow()
        loseKeystore()

        assertIs<RegistryUpdateException.NotNewer>(install(byRecovery(4)).exceptionOrNull())
        assertEquals(5L, install(byRecovery(5)).getOrThrow().sequence)
        assertEquals(listOf(5L), keystore.registryMacKeySeqs())
    }
}
