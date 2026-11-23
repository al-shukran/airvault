package app.lumio

import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Turning Biometric Unlock on opens the App Password step in the real vault only. In a Duress Password session that step would take the
 * real password on screen, so the switch does nothing there.
 */
class DeviceProtectionRoutesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    // Lazy, because the rule creates the temporary folder only after the test instance is built.
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }

    @Test
    fun turningOnOpensThePasswordStepInTheRealVault() {
        val real = openRealVault()

        assertFalse(real.isDecoy)
        assertTrue(turningOnOpensPasswordStep(real))
    }

    @Test
    fun turningOnOpensNothingInADuressSession() {
        val real = openRealVault()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()

        val decoy = unlock(DURESS)

        assertTrue(decoy.isDecoy)
        assertFalse(turningOnOpensPasswordStep(decoy))
    }

    /** Creates the vault and opens the real session, as the app does at unlock. */
    private fun openRealVault(): VaultSession {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        return unlock(APP)
    }

    private fun unlock(password: String): VaultSession =
        assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(password.toCharArray()) }).session

    private companion object {
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Hj3!Pw8Qd5Nk2Sr7Yt6"
    }
}
