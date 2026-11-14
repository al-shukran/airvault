package app.lensly.core.vault

import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
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

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
