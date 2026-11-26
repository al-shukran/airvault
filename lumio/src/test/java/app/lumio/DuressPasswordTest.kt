package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetupStore
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallets
import app.lumio.ui.firstrun.NewPasswordProblem
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
 * The Duress Password set: the new password is checked first, then the current one when the step asks for it, and then the set. The App Password
 * opens the real wallets and the Duress Password opens an empty Lumio, and the two passwords are never the same.
 */
class DuressPasswordTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    // Lazy, because the rule creates the temporary folder only after the test instance is built.
    private val vault: Vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), keys, clock, KDF) }

    /** The Keystore of the vault. While [lockOnHeaderCheck] is set, the first check of the vault header closes the session, as a lock mid-set does. */
    private var lockOnHeaderCheck = false
    private val memory = MemoryKeystore()
    private val keys: KeystoreBackend = object : KeystoreBackend by memory {
        override fun headerMac(message: ByteArray): ByteArray {
            if (lockOnHeaderCheck) {
                lockOnHeaderCheck = false
                holder.lock()
            }
            return memory.headerMac(message)
        }
    }
    private val holder: VaultHolder by lazy { VaultHolder(vault) }

    @Test
    fun theDuressPasswordOpensAnEmptyLumioAndTheAppPasswordOpensTheWallets() = runBlocking {
        create()

        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, REAL, DURESS, DURESS))
        holder.lock()

        val real = unlocked(unlockAfterDelay(REAL))
        assertFalse(real.isDecoy)
        assertEquals(listOf("Demo"), PairedWallets.list(real).map { it.name })
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        assertTrue(decoy.isDecoy)
        assertEquals(emptyList(), PairedWallets.list(decoy), "The decoy holds no paired wallet.")
        assertEquals(emptySet(), FinishSetupStore.read(decoy).done, "The decoy starts with nothing ticked.")
        decoy.close()
    }

    @Test
    fun theFirstRunSetsItWithNoCurrentPasswordAndTicksTheChecklist() = runBlocking {
        create()

        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, current = null, password = DURESS, repeat = DURESS))

        assertEquals(setOf(FinishItem.DURESS), FinishSetupStore.read(holder.openSession()!!).done)
        holder.lock()
        assertTrue(unlocked(unlockAfterDelay(DURESS)).also { it.close() }.isDecoy)
    }

    @Test
    fun aDuressPasswordThatIsTheAppPasswordIsRefusedAndSetsNothing() = runBlocking {
        create()

        assertEquals(
            PasswordChange.NewPassword(NewPasswordProblem.SameAsLumio),
            setLumioDuressPassword(holder, REAL, REAL, REAL),
        )

        assertEquals(emptySet(), FinishSetupStore.read(holder.openSession()!!).done, "Nothing is ticked.")
        holder.lock()
        // The App Password still opens the real vault, and no other password opens a decoy.
        assertFalse(unlocked(unlockAfterDelay(REAL)).also { it.close() }.isDecoy)
        assertEquals(UnlockResult.Rejected, unlockAfterDelay(DURESS))
    }

    @Test
    fun aWrongCurrentPasswordSetsNothing() = runBlocking {
        create()

        assertEquals(PasswordChange.WrongCurrent, setLumioDuressPassword(holder, WRONG, DURESS, DURESS))

        holder.lock()
        assertEquals(UnlockResult.Rejected, unlockAfterDelay(DURESS))
        assertFalse(unlocked(unlockAfterDelay(REAL)).also { it.close() }.isDecoy)
    }

    @Test
    fun theNewPasswordIsCheckedBeforeTheCurrentOneIsTried() = runBlocking {
        create()

        // The current password is wrong, so a WrongCurrent here would mean the new password was never checked first.
        assertEquals(PasswordChange.NewPassword(NewPasswordProblem.TooShort), setLumioDuressPassword(holder, WRONG, "short", "short"))
        assertEquals(PasswordChange.NewPassword(NewPasswordProblem.Mismatch), setLumioDuressPassword(holder, WRONG, DURESS, REAL))
    }

    @Test
    fun theAppPasswordCannotBeChangedToTheDuressPassword() = runBlocking {
        create()
        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, REAL, DURESS, DURESS))

        // One password must never open both vaults, so the change is refused and the App Password stays.
        assertIs<PasswordChange.NewPassword>(changeLumioPassword(holder, REAL, DURESS, DURESS))

        holder.lock()
        assertFalse(unlocked(unlockAfterDelay(REAL)).also { it.close() }.isDecoy)
    }

    @Test
    fun aDecoySessionSetsItsOwnPasswordAndNeverTheRealOne() = runBlocking {
        create()
        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, REAL, DURESS, DURESS))
        holder.lock()
        holder.adopt(unlocked(unlockAfterDelay(DURESS)), holder.unlockEpoch())

        // In the decoy, the current password is the duress one. The real password is refused as a new one, as a weak password is.
        assertEquals(PasswordChange.WrongCurrent, setLumioDuressPassword(holder, REAL, OTHER, OTHER))
        assertIs<PasswordChange.NewPassword>(setLumioDuressPassword(holder, DURESS, REAL, REAL))
        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, DURESS, OTHER, OTHER))
        holder.lock()

        assertEquals(UnlockResult.Rejected, unlockAfterDelay(DURESS), "The old duress password opens nothing.")
        assertTrue(unlocked(unlockAfterDelay(OTHER)).also { it.close() }.isDecoy)
        val real = unlocked(unlockAfterDelay(REAL))
        assertEquals(listOf("Demo"), PairedWallets.list(real).map { it.name }, "The real wallets are untouched.")
        real.close()
    }

    @Test
    fun aSetWithTheVaultLockedSetsNothing() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(REAL.toCharArray()))

        assertEquals(PasswordChange.Locked, setLumioDuressPassword(holder, current = null, password = DURESS, repeat = DURESS))

        assertEquals(UnlockResult.Rejected, unlockAfterDelay(DURESS))
    }

    @Test
    fun aChecklistRecordThatDoesNotParseDoesNotBlockTheSetAndIsReplaced() = runBlocking {
        create()
        FinishSetupStore.tick(holder.openSession()!!, FinishItem.PAIR)
        holder.openSession()!!.putVaultRecord(FinishSetupStore.RECORD, "not json".encodeToByteArray())

        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, REAL, DURESS, DURESS))

        assertEquals(setOf(FinishItem.DURESS), FinishSetupStore.read(holder.openSession()!!).done, "The unreadable record is reset, then ticked.")
        holder.lock()
        assertTrue(unlocked(unlockAfterDelay(DURESS)).also { it.close() }.isDecoy)
    }

    @Test
    fun aLockMidSetLeavesTheRowNotSet() = runBlocking {
        create()
        lockOnHeaderCheck = true

        assertEquals(PasswordChange.Locked, setLumioDuressPassword(holder, current = null, password = DURESS, repeat = DURESS))

        assertFalse(lockOnHeaderCheck, "The lock ran inside the set.")
        val session = unlocked(unlockAfterDelay(REAL))
        assertEquals(emptySet(), FinishSetupStore.read(session).done, "No set returned Enabled, so the row reads Not Set.")
        session.close()
    }

    @Test
    fun aRefusedSetWithNoTickBeforeLeavesTheRowNotSet() = runBlocking {
        create()

        assertEquals(PasswordChange.NewPassword(NewPasswordProblem.SameAsLumio), setLumioDuressPassword(holder, REAL, REAL, REAL))

        assertEquals(emptySet(), FinishSetupStore.read(holder.openSession()!!).done)
    }

    @Test
    fun aRefusedSetKeepsTheTickOfTheDuressPasswordThatIsSet() = runBlocking {
        create()
        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, REAL, DURESS, DURESS))

        assertEquals(PasswordChange.NewPassword(NewPasswordProblem.SameAsLumio), setLumioDuressPassword(holder, REAL, REAL, REAL))

        assertEquals(setOf(FinishItem.DURESS), FinishSetupStore.read(holder.openSession()!!).done)
    }

    /** Creates the vault with [REAL], opens it into the holder as the gate does, and pairs one wallet in it. */
    private suspend fun create() {
        assertEquals(CreateResult.Created, vault.create(REAL.toCharArray()))
        val session = unlocked(unlockAfterDelay(REAL))
        PairedWallets.save(session, PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Main"))), nowMs = 100L)
        holder.adopt(session, holder.unlockEpoch())
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
        const val REAL = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val OTHER = "Rk5!Wm8#Xc2\$Lp7Vb4Q"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
