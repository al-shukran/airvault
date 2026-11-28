package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.MemoryKeystore
import java.io.File
import java.security.KeyStoreException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A recovery package that revokes the key of the installed package replaces it with a lower sequence, so the floor goes down and the
 * Keystore keys above the new floor must go with it. A key left above the new seal would read the seal as unverified and refuse every
 * package below that key.
 */
class RegistryFloorLoweringTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for a process that dies: it is not an [Exception], so the install's own cleanup does not run. */
    private class ProcessDied : Error()

    private val keys = TestPackages.TRUSTED_BOTH
    private val memory = MemoryKeystore()
    private var failDelete = false
    private var dieOnDelete = false
    private var dieOnSeal = false
    private val keystore: KeystoreBackend = object : KeystoreBackend by memory {
        override fun deleteRegistryMacKeysExcept(keepSeq: Long?) {
            if (dieOnDelete) throw ProcessDied()
            if (failDelete) throw KeyStoreException("busy")
            memory.deleteRegistryMacKeysExcept(keepSeq)
        }
    }
    private val baked: Registry = RegistryLoader.load { path -> File("src/main/assets", path).takeIf { it.isFile }?.readText() }
    private val dir: File get() = folder.root.resolve("registry")
    private val leaked = TestPackages.OTHER_KEY_ID

    private fun recovery(sequence: Long) =
        TestPackages.signed(TestPackages.payload(sequence = sequence, minSchemaVersion = 6, revokedKeys = listOf(leaked)))

    private fun leaked(sequence: Long) =
        TestPackages.signed(TestPackages.payload(sequence = sequence, minSchemaVersion = 6), TestPackages.OTHER_SEED)

    private fun install(bytes: ByteArray) = RegistryUpdate.install(
        bytes, keys, baked, dir, keystore,
        write = { file, content -> if (dieOnSeal && file.name == RegistrySeal.FILE) throw ProcessDied() else file.writeBytes(content) },
    )

    private fun unverified() = RegistryUpdate.withInstalled(baked, dir, keys, keystore).installedUpdateUnverified

    @Test
    fun aFailedKeyDeleteFailsTheInstallAndLeavesTheOldStoreInUse() {
        install(leaked(Long.MAX_VALUE)).getOrThrow()
        failDelete = true

        assertIs<KeyStoreException>(install(recovery(5)).exceptionOrNull()?.let { it.cause ?: it })

        failDelete = false
        // The lowered key is left behind by the failed cleanup too, but it is below the sealed floor, so the old seal still verifies.
        assertEquals(Long.MAX_VALUE, memory.registryMacKeySeqs().max())
        // The revocation of the leaked key stands, so the old package no longer counts, but the store is not stuck: the retry installs.
        assertTrue(install(recovery(5)).isSuccess)
        assertFalse(unverified())
        assertTrue(install(recovery(6)).isSuccess)
    }

    @Test
    fun aLoweringInstallLeavesOnlyTheLoweredKeyAndASealThatVerifies() {
        install(leaked(1_000_000)).getOrThrow()

        install(recovery(5)).getOrThrow()

        assertEquals(listOf(5L), memory.registryMacKeySeqs())
        assertIs<RegistrySeal.Reading.Sealed>(RegistrySeal.read(dir, keystore))
        assertFalse(unverified())
        assertTrue(install(recovery(6)).isSuccess)
    }

    @Test
    fun aCrashBeforeTheOldKeyIsDeletedIsHealedByTheSamePackage() {
        install(leaked(1_000_000)).getOrThrow()
        dieOnDelete = true
        install(recovery(5))
        dieOnDelete = false

        assertEquals(setOf(5L, 1_000_000L), memory.registryMacKeySeqs().toSet())
        assertTrue(install(recovery(5)).isSuccess)
        assertEquals(listOf(5L), memory.registryMacKeySeqs())
        assertFalse(unverified())
        assertTrue(install(recovery(6)).isSuccess)
    }

    @Test
    fun aCrashAfterTheOldKeyIsDeletedAndBeforeTheSealIsHealedByTheSamePackage() {
        install(leaked(1_000_000)).getOrThrow()
        dieOnSeal = true
        install(recovery(5))
        dieOnSeal = false

        assertEquals(listOf(5L), memory.registryMacKeySeqs())
        assertTrue(install(recovery(5)).isSuccess)
        assertIs<RegistrySeal.Reading.Sealed>(RegistrySeal.read(dir, keystore))
        assertFalse(unverified())
        assertTrue(install(recovery(6)).isSuccess)
    }

    @Test
    fun aFailedSealWriteAfterTheOldKeyIsDeletedKeepsTheLoweredKey() {
        install(leaked(1_000_000)).getOrThrow()
        val failing = RegistryUpdate.install(
            recovery(5), keys, baked, dir, keystore,
            write = { file, content -> if (file.name == RegistrySeal.FILE) error("disk full") else file.writeBytes(content) },
        )

        assertTrue(failing.isFailure)
        assertEquals(listOf(5L), memory.registryMacKeySeqs())
        assertTrue(install(recovery(5)).isSuccess)
        assertFalse(unverified())
    }

    /** The keys {5, 10, [leaked sequence]}: the stray middle key is what an earlier failed cleanup left behind. */
    private fun installWithStrayMiddleKey(): Long {
        val floor = 1_000_000L
        install(leaked(floor)).getOrThrow()
        memory.createRegistryMacKey(10)
        return floor
    }

    @Test
    fun aMiddleKeyIsNeverLeftAboveTheRetrysSequenceWhenTheDeleteAndTheCleanupBothFail() {
        val floor = installWithStrayMiddleKey()
        // The delete removes one key and then fails, and the cleanup's delete fails too.
        memory.registryDeletesLeft = 1

        assertTrue(install(recovery(5)).isFailure)

        memory.registryDeletesLeft = null
        // Keys go lowest first, so the one that is left above the new floor is the old floor's, which still verifies the old seal.
        assertEquals(setOf(5L, floor), memory.registryMacKeySeqs().toSet())
        assertIs<RegistrySeal.Reading.Sealed>(RegistrySeal.read(dir, keystore))
        assertTrue(install(recovery(5)).isSuccess)
        assertEquals(listOf(5L), memory.registryMacKeySeqs())
        assertIs<RegistrySeal.Reading.Sealed>(RegistrySeal.read(dir, keystore))
        assertFalse(unverified())
    }

    @Test
    fun aDeleteThatFailsAtAnyPointLeavesAKeyAndTheSamePackageInstallsAgain() {
        // Fresh state per budget: the keystore, the store and the failure are all rebuilt by the helper.
        for (budget in 0..2) {
            val mem = MemoryKeystore()
            val dir = folder.newFolder("budget$budget").resolve("registry")
            RegistryUpdate.install(leaked(1_000_000), keys, baked, dir, mem).getOrThrow()
            mem.createRegistryMacKey(10)
            mem.registryDeletesLeft = budget

            val first = RegistryUpdate.install(recovery(5), keys, baked, dir, mem)

            mem.registryDeletesLeft = null
            val left = mem.registryMacKeySeqs()
            assertTrue(left.isNotEmpty(), "budget $budget left no key")
            // Either the old floor's key is still the highest, or every stale key is gone.
            assertTrue(1_000_000L in left || left == listOf(5L), "budget $budget left $left")
            // Two deletes remove both stale keys, so that install succeeds and there is nothing to retry.
            assertEquals(budget >= 2, first.isSuccess, "budget $budget")
            if (first.isFailure) assertTrue(RegistryUpdate.install(recovery(5), keys, baked, dir, mem).isSuccess, "budget $budget")
            assertEquals(listOf(5L), mem.registryMacKeySeqs())
            assertIs<RegistrySeal.Reading.Sealed>(RegistrySeal.read(dir, mem))
        }
    }
}
