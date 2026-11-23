package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.flow.BiometricEnablePorts
import app.lensly.ui.flow.PasswordCheck
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetup
import app.lumio.data.config.FinishSetupStore
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
 * Turning Device Protection on ticks its item off the Finish Setting Up checklist, and only a seal that worked does. The vault is real, on an
 * in-memory Keystore. The seal is a fake that passes or fails as the test says, because the Android Keystore is not on the JVM.
 */
class DeviceProtectionPortsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    // Lazy, because the rule creates the temporary folder only after the test instance is built.
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }
    private val holder by lazy { VaultHolder(vault) }

    @Test
    fun aSealThatWorksTicksDeviceProtectionOffTheChecklist() {
        val session = openVault()

        assertTrue(runBlocking { DeviceProtectionPorts(FakeSeal(seals = true), holder).enable() })

        assertEquals(setOf(FinishItem.DEVICE_PROTECTION), FinishSetupStore.read(session).done)
    }

    @Test
    fun aSealThatDoesNotWorkLeavesTheChecklistAsItWas() {
        val session = openVault()

        assertFalse(runBlocking { DeviceProtectionPorts(FakeSeal(seals = false), holder).enable() })

        assertEquals(FinishSetup(), FinishSetupStore.read(session))
    }

    /** Creates the vault and opens it into [holder], as the app does at unlock. */
    private fun openVault(): VaultSession {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        val session = assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(APP.toCharArray()) }).session
        assertTrue(holder.adopt(session, holder.unlockEpoch()))
        return session
    }

    /** Stands in for the shared ports: the password is always right, and the seal passes or fails as the test says. */
    private class FakeSeal(private val seals: Boolean) : BiometricEnablePorts {
        override suspend fun checkPassword(password: CharArray): PasswordCheck = PasswordCheck.Accepted

        override suspend fun enable(): Boolean = seals
    }

    private companion object {
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
    }
}
