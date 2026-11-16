package app.lumio.ui.settings

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.settings.ChangeOutcome
import app.lensly.ui.settings.changeAppPassword
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lumio's App Password change, on Lumio's own vault. The change re-wraps the vault key under the new password, as Lensly's does, so the records
 * stay readable under the new password, the old password stops opening the vault, and a refused new password changes nothing.
 */
class LumioChangePasswordTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    // Lazy, because the rule creates the temporary folder only after the test instance is built.
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }
    private val holder by lazy { VaultHolder(vault) }

    @Test
    fun aPasswordChangeKeepsTheRecordsAndTheOldPasswordStopsWorking() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(OLD.toCharArray()))
        open(OLD)
        holder.openSession()!!.putVaultRecord(PROBE, PROBE_VALUE.toByteArray())

        assertEquals(ChangeOutcome.Changed, changeAppPassword(holder, NEW.toCharArray()))

        holder.lock()
        assertIs<UnlockResult.Rejected>(unlockAfterDelay(OLD))
        val reopened = unlocked(unlockAfterDelay(NEW))
        assertEquals(PROBE_VALUE, reopened.getVaultRecord(PROBE)!!.decodeToString())
        reopened.close()
    }

    @Test
    fun aRefusedNewPasswordChangesNothing() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(OLD.toCharArray()))
        open(OLD)

        assertIs<ChangeOutcome.Refused>(changeAppPassword(holder, WEAK.toCharArray()))

        holder.lock()
        unlocked(unlockAfterDelay(OLD)).close()
    }

    @Test
    fun aChangeWithTheVaultLockedChangesNothing() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(OLD.toCharArray()))

        assertEquals(ChangeOutcome.Locked, changeAppPassword(holder, NEW.toCharArray()))

        unlocked(unlockAfterDelay(OLD)).close()
    }

    /** Opens the vault with [password] and keeps the session in the holder, as the gate does. */
    private suspend fun open(password: String) {
        holder.adopt(unlocked(unlockAfterDelay(password)), holder.unlockEpoch())
    }

    /** Moves the clock past any failure delay, then tries [password]. */
    private suspend fun unlockAfterDelay(password: String): UnlockResult {
        clock.advance(ONE_DAY_MS)
        return vault.unlock(password.toCharArray())
    }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertIs<UnlockResult.Unlocked>(result)
        return result.session
    }

    private companion object {
        const val OLD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val NEW = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val WEAK = "password1234"
        const val PROBE = "lumio-change-probe"
        const val PROBE_VALUE = "kept"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
