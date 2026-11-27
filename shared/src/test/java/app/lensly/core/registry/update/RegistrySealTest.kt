package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultException
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

/** The seal of the registry store: Lensly 1.2.0 stores adopted once, a Keystore that fails never blocks signing, and recovery. */
class RegistrySealTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED
    private val keystore = MemoryKeystore()
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")

    private fun signedAt(sequence: Long, whitelist: String? = null): ByteArray =
        TestPackages.signed(TestPackages.payload(sequence = sequence, whitelist = whitelist))

    private fun swap(meaning: String) = TestPackages.LINEA_SWAP.replace("Swap tokens on the test route", meaning)

    private fun view(with: KeystoreBackend = keystore): Registry = RegistryUpdate.withInstalled(baked, dir, keys, with)

    /** A store as Lensly 1.2.0 wrote it: the files and the decimal record, and no seal and no Keystore key. */
    private fun write1_2_0Store(installed: Long, whitelistCarrier: Long?, whitelist: String? = null) {
        dir.mkdirs()
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(signedAt(installed, whitelist))
        if (whitelistCarrier != null) {
            File(dir, RegistryUpdate.WHITELIST_FILE).writeBytes(signedAt(whitelistCarrier, swap("Carried")))
        }
        File(dir, RegistryUpdate.LEGACY_SEQUENCE_FILE).writeText(installed.toString())
    }

    @Test
    fun aVersion1_2_0StoreIsAdoptedOnTheFirstRunAndNotTreatedAsTampered() {
        write1_2_0Store(installed = 6, whitelistCarrier = 5)

        val catalog = RegistryCatalog(baked, dir, keys, keystore)

        assertNotNull(catalog.current().network(4242L))
        assertFalse(catalog.current().installedUpdateUnverified)
        assertEquals(listOf("Carried"), catalog.current().whitelistEntries().map { it.meaning })
        assertEquals(6L, catalog.installed()?.sequence)
        assertTrue(File(dir, "registry-seal").isFile)
        assertFalse(File(dir, RegistryUpdate.LEGACY_SEQUENCE_FILE).exists())
        assertEquals(listOf(6L), keystore.registryMacKeySeqs())
    }

    @Test
    fun anAdoptedStoreIsProtectedFromThenOn() {
        write1_2_0Store(installed = 6, whitelistCarrier = 5)
        RegistryCatalog(baked, dir, keys, keystore)

        File(dir, RegistryUpdate.WHITELIST_FILE).writeBytes(signedAt(2, swap("Older swap")))
        assertFalse("Older swap" in view().whitelistEntries().map { it.meaning })
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(signedAt(3))
        assertTrue(view().installedUpdateUnverified)
        File(dir, "registry-seal").delete()
        RegistryUpdate.adoptUnsealed(dir, keys, keystore)
        assertFalse(File(dir, "registry-seal").exists())
        assertIs<RegistryUpdateException.NotNewer>(
            RegistryUpdate.install(signedAt(4), keys, baked, dir, keystore).exceptionOrNull(),
        )
    }

    @Test
    fun aVersion1_2_0StoreIsAdoptedByAnInstallToo() {
        write1_2_0Store(installed = 6, whitelistCarrier = 5)

        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(6), keys, baked, dir, keystore).exceptionOrNull())
        RegistryUpdate.install(signedAt(7), keys, baked, dir, keystore).getOrThrow()

        assertEquals(listOf("Carried"), view().whitelistEntries().map { it.meaning })
    }

    @Test
    fun aVersion1_2_0RecordAboveTheInstalledPackageKeepsTheFloorUp() {
        write1_2_0Store(installed = 6, whitelistCarrier = null)
        File(dir, RegistryUpdate.LEGACY_SEQUENCE_FILE).writeText("9")

        val catalog = RegistryCatalog(baked, dir, keys, keystore)

        assertTrue(catalog.current().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(catalog.install(signedAt(8)).exceptionOrNull())
    }

    @Test
    fun aKeystoreThatCannotListItsKeysFallsBackToTheBuiltInCatalogAndNeverThrows() {
        write1_2_0Store(installed = 6, whitelistCarrier = null)
        val broken = object : KeystoreBackend by keystore {
            override fun registryMacKeySeqs(): List<Long> = throw VaultException("The Keystore is unavailable.")
        }

        val catalog = RegistryCatalog(baked, dir, keys, broken)

        assertTrue(catalog.current().installedUpdateUnverified)
        assertEquals(baked.networks(), catalog.current().networks())
        assertTrue(catalog.install(signedAt(7)).isFailure)
        assertTrue(File(dir, RegistryUpdate.LEGACY_SEQUENCE_FILE).exists())
        // The Keystore is back: the next start adopts the store and reads it as before.
        assertFalse(RegistryCatalog(baked, dir, keys, keystore).current().installedUpdateUnverified)
    }

    @Test
    fun aKeystoreThatRefusesTheNewKeyFailsTheInstallAndLeavesTheStoreAsItWas() {
        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        val refusing = object : KeystoreBackend by keystore {
            override fun createRegistryMacKey(seq: Long) = throw VaultException("The Keystore refused the key.")
        }

        assertTrue(RegistryUpdate.install(signedAt(6), keys, baked, dir, refusing).isFailure)

        assertEquals(5L, RegistryUpdate.installedPackage(baked, dir, keys, keystore)?.sequence)
        assertFalse(view().installedUpdateUnverified)
    }

    @Test
    fun aDeletedSealIsUnverifiedNeverAdoptedAndTheNextInstallRecovers() {
        RegistryUpdate.install(signedAt(5, whitelist = swap("Old")), keys, baked, dir, keystore).getOrThrow()
        File(dir, "registry-seal").delete()

        RegistryUpdate.adoptUnsealed(dir, keys, keystore)
        assertFalse(File(dir, "registry-seal").exists())
        assertTrue(view().installedUpdateUnverified)

        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(4), keys, baked, dir, keystore).exceptionOrNull())
        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        assertFalse(view().installedUpdateUnverified)
        assertNotNull(view().network(4242L))
        // The carrier of the kept whitelist cannot be shown after an unverified spell, so it is not kept.
        assertTrue(view().whitelistEntries().none { it.meaning == "Old" })
    }

    @Test
    fun aCrashAfterTheKeyBeforeTheSealIsRecoveredByTheSameSequence() {
        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        // Install of 7 made its key and wrote the package, then died before the seal.
        keystore.createRegistryMacKey(7)
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(signedAt(7))

        assertNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore))
        assertTrue(view().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(6), keys, baked, dir, keystore).exceptionOrNull())
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).exceptionOrNull())

        RegistryUpdate.install(signedAt(7), keys, baked, dir, keystore).getOrThrow()
        assertEquals(listOf(7L), keystore.registryMacKeySeqs())
        assertFalse(view().installedUpdateUnverified)
        assertEquals(7L, RegistryUpdate.installedPackage(baked, dir, keys, keystore)?.sequence)
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(7), keys, baked, dir, keystore).exceptionOrNull())
    }

    @Test
    fun aLostKeyIsRecoveredByTheSameSequenceAndNeverByALowerOne() {
        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        keystore.deleteRegistryMacKeysExcept(null)

        assertTrue(view().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(4), keys, baked, dir, keystore).exceptionOrNull())

        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        assertEquals(listOf(5L), keystore.registryMacKeySeqs())
        assertFalse(view().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).exceptionOrNull())
    }

    @Test
    fun recoveringBySequenceDoesNotBringBackAnOlderWhitelist() {
        RegistryUpdate.install(signedAt(3, whitelist = swap("Older swap")), keys, baked, dir, keystore).getOrThrow()
        val older = File(dir, RegistryUpdate.WHITELIST_FILE).readBytes()
        RegistryUpdate.install(signedAt(5, whitelist = swap("Newer swap")), keys, baked, dir, keystore).getOrThrow()
        File(dir, "registry-seal").delete()
        File(dir, RegistryUpdate.WHITELIST_FILE).writeBytes(older)

        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()

        assertTrue(view().whitelistEntries().none { it.meaning == "Older swap" })
        assertFalse(view().installedUpdateUnverified)
    }

    @Test
    fun aKeyNewerThanTheSealAfterACrashIsUnverifiedUntilAnInstallReachesIt() {
        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        keystore.createRegistryMacKey(7)

        assertNull(RegistryUpdate.installedPackage(baked, dir, keys, keystore))
        assertTrue(view().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(6), keys, baked, dir, keystore).exceptionOrNull())
        RegistryUpdate.install(signedAt(8), keys, baked, dir, keystore).getOrThrow()
        assertEquals(listOf(8L), keystore.registryMacKeySeqs())
        assertFalse(view().installedUpdateUnverified)
    }

    @Test
    fun aDeletedInstalledPackageIsFlaggedAndTheSamePackageInstalledAgainRepairsIt() {
        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()
        File(dir, RegistryUpdate.INSTALLED_FILE).delete()

        assertTrue(view().installedUpdateUnverified)
        assertTrue(RegistryCatalog(baked, dir, keys, keystore).current().installedUpdateUnverified)
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(4), keys, baked, dir, keystore).exceptionOrNull())

        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()

        assertFalse(view().installedUpdateUnverified)
        assertNotNull(view().network(4242L))
        assertIs<RegistryUpdateException.NotNewer>(RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).exceptionOrNull())
    }

    @Test
    fun aRegistryFolderLeftEmptyByAnInterruptedFirstInstallDoesNotRefuseTheNextOne() {
        dir.mkdirs()

        RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).getOrThrow()

        assertEquals(5L, RegistryUpdate.installedPackage(baked, dir, keys, keystore)?.sequence)
    }

    @Test
    fun aKeystoreThatRefusesTheFirstKeyLeavesNoFolderAndTheNextInstallSucceeds() {
        var refusals = 1
        val flaky = object : KeystoreBackend by keystore {
            override fun createRegistryMacKey(seq: Long) =
                if (refusals-- > 0) throw VaultException("The Keystore refused the key.") else keystore.createRegistryMacKey(seq)
        }

        assertTrue(RegistryUpdate.install(signedAt(5), keys, baked, dir, flaky).isFailure)
        assertFalse(dir.exists())

        RegistryUpdate.install(signedAt(5), keys, baked, dir, flaky).getOrThrow()
        assertTrue(File(dir, RegistryUpdate.INSTALLED_FILE).isFile)
        assertTrue(File(dir, RegistrySeal.FILE).isFile)
        assertFalse(view().installedUpdateUnverified)
    }

    @Test
    fun aFolderWithAnUnreadablePackageAndNoFloorIsStillRefused() {
        dir.mkdirs()
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(byteArrayOf(1, 2, 3))

        assertIs<RegistryUpdateException.SequenceUnknown>(RegistryUpdate.install(signedAt(5), keys, baked, dir, keystore).exceptionOrNull())
    }

    @Test
    fun aPackageWithoutAWhitelistThatDropsANetworkTheKeptWhitelistUsesIsRefusedAndStoresNothing() {
        RegistryUpdate.install(signedAt(5, whitelist = swap("Kept")), keys, baked, dir, keystore).getOrThrow()
        val installedBefore = File(dir, RegistryUpdate.INSTALLED_FILE).readBytes()
        val bare = TestPackages.signed(TestPackages.payload(sequence = 6, networks = "", tokens = ""))

        assertIs<RegistryUpdateException.Conflict>(RegistryUpdate.install(bare, keys, baked, dir, keystore).exceptionOrNull())

        assertTrue(installedBefore.contentEquals(File(dir, RegistryUpdate.INSTALLED_FILE).readBytes()))
        assertEquals(listOf(5L), keystore.registryMacKeySeqs())
        assertEquals(listOf("Kept"), view().whitelistEntries().map { it.meaning })
        assertFalse(view().installedUpdateUnverified)
        // Control: the same package that keeps the network installs.
        RegistryUpdate.install(signedAt(6), keys, baked, dir, keystore).getOrThrow()
        assertEquals(listOf("Kept"), view().whitelistEntries().map { it.meaning })
    }

    @Test
    fun aStoredMergeThatDoesNotBuildFallsBackToTheBuiltInCatalogInsteadOfThrowing() {
        RegistryUpdate.install(signedAt(5, whitelist = swap("Kept")), keys, baked, dir, keystore).getOrThrow()
        // A package that dropped network 4242 is forced on disk under a seal that still names the carrier of the whitelist on 4242.
        val bare = TestPackages.signed(TestPackages.payload(sequence = 6, networks = "", tokens = ""))
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(bare)
        val carrier = RegistrySeal.digest(File(dir, RegistryUpdate.WHITELIST_FILE).readBytes())
        val forged = RegistrySeal.Seal(6, 5, 0, RegistrySeal.digest(bare), carrier, RegistrySeal.NONE)
        File(dir, RegistrySeal.FILE).writeBytes(RegistrySeal.create(forged, keystore))
        RegistrySeal.keepOnly(6, keystore)

        val catalog = RegistryCatalog(baked, dir, keys, keystore)

        assertTrue(catalog.current().installedUpdateUnverified)
        assertEquals(baked.networks(), catalog.current().networks())
    }
}
