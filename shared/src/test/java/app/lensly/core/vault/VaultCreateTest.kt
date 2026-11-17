package app.lensly.core.vault

import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Vault creation, when the Keystore refuses the key the Failure Delay needs. */
class VaultCreateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()

    @Test
    fun aRefusedFailureDelayKeyLeavesNoKeysAndNoFilesBehind() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val keys = MemoryKeystore()
        // The hardware key is made, and then this refuses the Delay State key.
        val refusing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey = throw GeneralSecurityException("refused")
        }
        val vault = Vault(layout, refusing, clock, KDF, readBootCount = { 7 })

        assertEquals(CreateResult.DeviceUnsupported, runBlocking { vault.create(APP.toCharArray()) })

        // Nothing is left behind: no vault, no file, and no key.
        assertFalse(layout.exists())
        assertFalse(layout.root.exists())
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
        assertFailsWith<VaultException> { keys.hardwareKey() }
    }

    @Test
    fun aCreateIsRefusedOverAKeyFileAndWalletsLeftWithoutAHeader() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val keys = MemoryKeystore()
        val vault = Vault(layout, keys, clock, KDF, readBootCount = { 7 })
        // The hardware key of an old vault, and its key file and wallet folder, with the header gone.
        val oldKey = keys.createHardwareKey()
        layout.root.mkdirs()
        layout.vaultKey.writeBytes(byteArrayOf(4, 5, 6))
        layout.wallets.mkdirs()

        assertFailsWith<IllegalStateException> { runBlocking { vault.create(APP.toCharArray()) } }

        // The refusal writes nothing, and the old hardware key is still the one in the Keystore.
        assertFalse(layout.header.exists())
        assertContentEquals(byteArrayOf(4, 5, 6), layout.vaultKey.readBytes())
        assertContentEquals(oldKey.encoded, keys.hardwareKey().encoded)
    }

    @Test
    fun aCreateIsRefusedOverAHeaderLeftWithoutAKeyFile() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val keys = MemoryKeystore()
        val vault = Vault(layout, keys, clock, KDF, readBootCount = { 7 })
        val oldKey = keys.createHardwareKey()
        layout.root.mkdirs()
        layout.header.writeBytes(byteArrayOf(1, 2, 3))

        assertFailsWith<IllegalStateException> { runBlocking { vault.create(APP.toCharArray()) } }

        assertContentEquals(byteArrayOf(1, 2, 3), layout.header.readBytes())
        assertFalse(layout.vaultKey.exists())
        assertContentEquals(oldKey.encoded, keys.hardwareKey().encoded)
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
