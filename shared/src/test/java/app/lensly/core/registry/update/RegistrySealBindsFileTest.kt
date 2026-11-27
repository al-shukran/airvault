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
 * The seal names the installed package file by hash, so a file planted in its place is not "the package that set the floor" and its
 * revocations do not count, whatever key signed it. Three test keys: the first is a recovery key, the second the key that leaked, and the
 * third a release key that signs the legitimate packages.
 */
class RegistrySealBindsFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")
    private val installedFile: File get() = File(dir, RegistryUpdate.INSTALLED_FILE)

    private val active = listOf(
        TrustedKey(TestPackages.PUBLIC_BYTES, role = KeyRole.RECOVERY),
        TrustedKey(TestPackages.OTHER_PUBLIC_BYTES),
        TrustedKey(TestPackages.THIRD_PUBLIC_BYTES),
    )
    private val buildRevokesLeaked = listOf(active[0], TrustedKey(TestPackages.OTHER_PUBLIC_BYTES, KeyStatus.REVOKED), active[2])
    private val leaked = TestPackages.OTHER_KEY_ID

    private fun signed(seed: ByteArray, sequence: Long, revoked: List<String> = emptyList(), whitelist: String? = null) =
        TestPackages.signed(TestPackages.payload(sequence = sequence, minSchemaVersion = 6, whitelist = whitelist, revokedKeys = revoked), seed)

    private fun install(bytes: ByteArray, build: List<TrustedKey> = active) = RegistryUpdate.install(bytes, build, baked, dir, keystore)

    @Test
    fun aPackagePlantedWithAnActiveButLeakedKeyCannotLetAPublicRecoveryPackageLowerTheFloor() {
        install(signed(TestPackages.THIRD_SEED, 10)).getOrThrow()
        installedFile.writeBytes(signed(TestPackages.OTHER_SEED, 10))

        val refused = install(signed(TestPackages.SEED, 8, revoked = listOf(leaked))).exceptionOrNull()

        assertIs<RegistryUpdateException.NotNewer>(refused)
        assertEquals(10L, RegistrySeal.newestFloor(keystore))
    }

    @Test
    fun aPackagePlantedWithAKeyTheBuildRevokesCannotLetAPublicPackageLowerTheFloor() {
        install(signed(TestPackages.THIRD_SEED, 10), buildRevokesLeaked).getOrThrow()
        installedFile.writeBytes(signed(TestPackages.OTHER_SEED, 10))

        val refused = install(signed(TestPackages.THIRD_SEED, 7), buildRevokesLeaked).exceptionOrNull()

        assertIs<RegistryUpdateException.NotNewer>(refused)
        assertEquals(10L, RegistrySeal.newestFloor(keystore))
    }

    @Test
    fun theSealedPackageStillYieldsToARecoveryPackageThatRevokesItsSigner() {
        install(signed(TestPackages.OTHER_SEED, 1_000_000)).getOrThrow()

        install(signed(TestPackages.SEED, 5, revoked = listOf(leaked))).getOrThrow()

        assertEquals(5L, RegistrySeal.newestFloor(keystore))
    }

    @Test
    fun aSealedPackagesRevocationsStayInForceWhenTheBuildLaterRevokesItsSigner() {
        val revoked = "0123456789abcdef"
        val thirdIsRecovery = listOf(active[0], active[1], TrustedKey(TestPackages.THIRD_PUBLIC_BYTES, role = KeyRole.RECOVERY))
        install(signed(TestPackages.THIRD_SEED, 10, revoked = listOf(revoked)), thirdIsRecovery).getOrThrow()
        // A later build lists the signer of the installed package as revoked.
        val build = listOf(active[0], active[1], TrustedKey(TestPackages.THIRD_PUBLIC_BYTES, KeyStatus.REVOKED, KeyRole.RECOVERY))

        val dropping = install(signed(TestPackages.OTHER_SEED, 12), build).exceptionOrNull()

        assertIs<RegistryUpdateException.Conflict>(dropping)
        install(signed(TestPackages.OTHER_SEED, 12, revoked = listOf(revoked)), build).getOrThrow()
    }

    @Test
    fun aFileSignedByAKeyTheBuildRevokesRevokesNothingUnlessTheSealNamesIt() {
        install(signed(TestPackages.THIRD_SEED, 10), buildRevokesLeaked).getOrThrow()
        // A planted file signed by the revoked key revokes the key that signs the legitimate packages. It is not the sealed file.
        installedFile.writeBytes(signed(TestPackages.OTHER_SEED, 11, revoked = listOf(TestPackages.THIRD_KEY_ID)))

        install(signed(TestPackages.THIRD_SEED, 12), buildRevokesLeaked).getOrThrow()
    }

    @Test
    fun aFileSignedByAnActiveKeyStillRevokesWhenItIsNotTheSealedOne() {
        install(signed(TestPackages.THIRD_SEED, 10)).getOrThrow()
        installedFile.writeBytes(signed(TestPackages.SEED, 11, revoked = listOf(TestPackages.THIRD_KEY_ID)))

        // Denial of updates, never the acceptance of a revoked key: the same refusal as before the seal named the file.
        assertIs<RegistryUpdateException.RevokedKey>(install(signed(TestPackages.THIRD_SEED, 12)).exceptionOrNull())
    }

    @Test
    fun aFreshDevicesPlantedPackageIsNeverAdopted() {
        val first = RegistryCatalog(baked, dir, active, keystore)
        assertFalse(first.current().installedUpdateUnverified)
        assertEquals(listOf(0L), keystore.registryMacKeySeqs())
        installedFile.writeBytes(signed(TestPackages.OTHER_SEED, 1_000_000))

        val restarted = RegistryCatalog(baked, dir, active, keystore)

        assertTrue(restarted.current().installedUpdateUnverified)
        assertEquals(null, restarted.installed())
        assertEquals(listOf(0L), keystore.registryMacKeySeqs())
        // The planted sequence pins nothing: an honest package still installs.
        restarted.install(signed(TestPackages.THIRD_SEED, 5)).getOrThrow()
        assertFalse(restarted.current().installedUpdateUnverified)
    }

    @Test
    fun aPlantedFileWithAHugeSequenceDoesNotPinTheFloor() {
        install(signed(TestPackages.THIRD_SEED, 10)).getOrThrow()
        installedFile.writeBytes(signed(TestPackages.OTHER_SEED, Long.MAX_VALUE))

        install(signed(TestPackages.THIRD_SEED, 11)).getOrThrow()

        assertEquals(11L, RegistrySeal.newestFloor(keystore))
    }

    @Test
    fun aDenylistCarrierSignedByAnotherKeyAndPlantedAtTheSealedSequenceIsNotUsed() {
        fun withDenylist(seed: ByteArray, sequence: Long, reason: String): ByteArray {
            val entry = """{"condition":"UNLIMITED","reason":"$reason","selector":"0x095ea7b3","severity":"WARN"}"""
            return TestPackages.signed(TestPackages.payload(sequence = sequence).dropLast(1) + ""","denylist":[$entry]}""", seed)
        }
        install(withDenylist(TestPackages.THIRD_SEED, 2, "Real")).getOrThrow()
        install(signed(TestPackages.THIRD_SEED, 3)).getOrThrow()
        assertFalse(RegistryUpdate.withInstalled(baked, dir, active, keystore).installedUpdateUnverified)

        File(dir, RegistryUpdate.DENYLIST_FILE).writeBytes(withDenylist(TestPackages.OTHER_SEED, 2, "Planted"))

        assertTrue(RegistryUpdate.withInstalled(baked, dir, active, keystore).installedUpdateUnverified)
    }

    @Test
    fun aWhitelistCarrierPlantedAtTheSealedSequenceIsNotUsed() {
        install(signed(TestPackages.THIRD_SEED, 2, whitelist = TestPackages.LINEA_SWAP)).getOrThrow()
        install(signed(TestPackages.THIRD_SEED, 3)).getOrThrow()
        val kept = RegistryUpdate.withInstalled(baked, dir, active, keystore)
        assertFalse(kept.installedUpdateUnverified)
        assertNotNull(kept.whitelist(4242L, "0x5555555555555555555555555555555555555555", "0x6e17e9f6"))

        val planted = TestPackages.LINEA_SWAP.replace("Swap tokens on the test route", "Planted")
        File(dir, RegistryUpdate.WHITELIST_FILE).writeBytes(signed(TestPackages.OTHER_SEED, 2, whitelist = planted))

        assertTrue(RegistryUpdate.withInstalled(baked, dir, active, keystore).installedUpdateUnverified)
    }
}
