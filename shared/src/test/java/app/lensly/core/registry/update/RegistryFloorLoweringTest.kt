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
}
