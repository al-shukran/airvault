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
import app.lumio.ui.firstrun.NewPasswordProblem
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Change Password on Lumio's page: the new password and its repeat are checked first, then the current password through the unlock path, and only
 * then the change. Every refusal leaves the App Password in place.
 */
class PasswordChangeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    // Lazy, because the rule creates the temporary folder only after the test instance is built.
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }
    private val holder by lazy { VaultHolder(vault) }

    @Test
    fun aWrongCurrentPasswordChangesNothing() = runBlocking {
        create()

        assertEquals(PasswordChange.WrongCurrent, changeLumioPassword(holder, WRONG, NEW, NEW))

        holder.lock()
        unlocked(unlockAfterDelay(OLD)).close()
    }

    @Test
    fun aNewPasswordIsCheckedBeforeTheCurrentOneIsTried() = runBlocking {
        create()

        // The current password here is wrong, so a result of WrongCurrent would mean the new password was never checked first.
        assertEquals(PasswordChange.NewPassword(NewPasswordProblem.TooShort), changeLumioPassword(holder, WRONG, "short", "short"))
        assertEquals(PasswordChange.NewPassword(NewPasswordProblem.Mismatch), changeLumioPassword(holder, WRONG, NEW, OTHER))

        holder.lock()
        unlocked(unlockAfterDelay(OLD)).close()
    }

    @Test
    fun theRightCurrentPasswordChangesTheAppPassword() = runBlocking {
        create()

        assertEquals(PasswordChange.Changed, changeLumioPassword(holder, OLD, NEW, NEW))

        holder.lock()
        assertEquals(UnlockResult.Rejected, unlockAfterDelay(OLD))
        unlocked(unlockAfterDelay(NEW)).close()
    }

    @Test
    fun aChangeWithTheVaultLockedChangesNothing() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(OLD.toCharArray()))

        assertEquals(PasswordChange.Locked, changeLumioPassword(holder, OLD, NEW, NEW))

        unlocked(unlockAfterDelay(OLD)).close()
    }

    /** Creates the vault with [OLD] and opens it into the holder, as the gate does. */
    private suspend fun create() {
        assertEquals(CreateResult.Created, vault.create(OLD.toCharArray()))
        holder.adopt(unlocked(unlockAfterDelay(OLD)), holder.unlockEpoch())
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
        const val OTHER = "Rk5!Wm8#Xc2\$Lp7Vb4Q"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
