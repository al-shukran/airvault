package app.lensly.ui.navigation

import app.lensly.app.DecoyVault
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.settings.FailureCapChoice
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The wrong password lockout is shared by both vaults, and only the real vault writes it. A decoy session shows its own copy: the
 * defaults before a change, the new values after it, and never the real vault's.
 */
class FailureCapDecoyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder

    @Before
    fun setUp() {
        vault = Vault(VaultLayout(File(tmp.root, "vault")), MemoryKeystore(), clock, KDF)
        holder = VaultHolder(vault)
        runBlocking {
            assertEquals(CreateResult.Created, vault.create(APP.toCharArray()))
            val real = open(APP)
            assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { DecoyVault.populate(it) })
            real.close()
        }
    }

    @Test
    fun aDecoyShowsTheLimitsItSavedAndTheRealVaultKeepsItsOwn() = runBlocking {
        holder.adopt(open(APP), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, APP.toCharArray(), 300_000L, 15))
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, DURESS.toCharArray(), 10_000L, 5))

        assertEquals(FailureCapChoice.TEN_SECONDS, readFailureCap(holder))
        assertEquals(5, readEraseAfter(holder))
        assertEquals(300_000L, vault.failureDelayCapMs())
        assertEquals(15, vault.failureEraseAfter())
    }

    @Test
    fun aDecoyShowsDefaultsBeforeAChangeWhateverTheRealVaultSaved() = runBlocking {
        holder.adopt(open(APP), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, APP.toCharArray(), 300_000L, 15))
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())

        assertEquals(FailureCapChoice.DEFAULT, readFailureCap(holder))
        assertEquals(FailureDelay.DEFAULT_WIPE_AFTER, readEraseAfter(holder))
    }

    @Test
    fun aDecoysLimitsStayForItsNextSessionAsARealVaultsDo() = runBlocking {
        holder.adopt(open(DURESS), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, DURESS.toCharArray(), 10_000L, 5))
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())

        assertEquals(FailureCapChoice.TEN_SECONDS, readFailureCap(holder))
        assertEquals(5, readEraseAfter(holder))
    }

    @Test
    fun aDecoysLimitsNeverShowInTheRealVault() = runBlocking {
        holder.adopt(open(DURESS), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, DURESS.toCharArray(), 10_000L, 5))
        holder.lock()

        holder.adopt(open(APP), holder.unlockEpoch())

        assertEquals(FailureCapChoice.DEFAULT, readFailureCap(holder))
        assertEquals(FailureDelay.DEFAULT_WIPE_AFTER, readEraseAfter(holder))
    }

    @Test
    fun aDecoyShowingMoreTriesThanTheRealVaultStillWarnsBeforeTheRealErase() = runBlocking {
        holder.adopt(open(APP), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, APP.toCharArray(), null, 5))
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, DURESS.toCharArray(), null, 20))
        wrongTries(4)

        // The vault erases at the real 5, so the 4th wrong try must warn "1 left" although the decoy shows 20.
        assertEquals(1, holder.triesBeforeErase())
    }

    @Test
    fun aDecoyWithTheDefaultEraseCountStillWarnsBeforeALowerRealErase() = runBlocking {
        holder.adopt(open(APP), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, APP.toCharArray(), null, 5))
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())
        wrongTries(4)

        assertEquals(1, holder.triesBeforeErase())
    }

    @Test
    fun aDecoyShowingFewerTriesThanTheRealVaultWarnsByTheShownCountAndDoesNotEraseAtIt() = runBlocking {
        holder.adopt(open(APP), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, APP.toCharArray(), null, 6))
        holder.lock()

        holder.adopt(open(DURESS), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, DURESS.toCharArray(), null, 4))
        wrongTries(2)
        assertEquals(2, holder.triesBeforeErase())
        wrongTries(1)
        assertEquals(1, holder.triesBeforeErase())
        wrongTries(1)

        // The accepted tell: the warning ran down to the shown 4 and nothing was erased. Erasing early would destroy the real vault.
        assertNull(holder.triesBeforeErase())
        assertTrue(vault.exists())
        assertEquals(6, vault.failureEraseAfter())
    }

    @Test
    fun aRealSessionWarnsAgainstTheRealEraseCount() = runBlocking {
        holder.adopt(open(APP), holder.unlockEpoch())
        assertEquals(FailureCapOutcome.Saved, saveFailureLimits(holder, APP.toCharArray(), null, 5))
        wrongTries(3)

        assertEquals(2, holder.triesBeforeErase())
    }

    private suspend fun wrongTries(count: Int) = repeat(count) {
        vault.unlock("wrong-$it".toCharArray())
        clock.advance(3_600_000L)
    }

    private fun open(password: String): VaultSession = runBlocking {
        (vault.unlock(password.toCharArray()) as UnlockResult.Unlocked).session
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
