package app.lensly.ui.flow

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
import app.lensly.ui.navigation.FailureCapOutcome
import app.lensly.ui.navigation.saveFailureLimits
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shared App Password check, and the other places the app only checks a password, take the App Password and refuse the Duress
 * Password as a wrong one. Only an unlock promotes the Decoy Vault, so typing the Duress Password here never erases the real vault.
 */
class DuressPasswordCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder
    private lateinit var realWallet: String

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        holder = VaultHolder(vault)
        runBlocking {
            assertEquals(CreateResult.Created, vault.create(APP.toCharArray()))
            val real = (vault.unlock(APP.toCharArray()) as UnlockResult.Unlocked).session
            realWallet = real.createWallet()
            assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { DecoyVault.populate(it) })
            real.close()
        }
    }

    @Test
    fun inTheRealVaultOnlyTheAppPasswordIsAccepted() {
        runBlocking { holder.adopt(open(APP), holder.unlockEpoch()) }

        assertEquals(PasswordCheck.Accepted, runBlocking { checkAppPassword(holder, APP.toCharArray()) })
        assertEquals(PasswordCheck.Rejected, runBlocking { checkAppPassword(holder, DURESS.toCharArray()) })
    }

    @Test
    fun theDuressPasswordIsRefusedByTheCheckAndTheFailureLimitsAndPromotesNothing() {
        runBlocking { holder.adopt(open(APP), holder.unlockEpoch()) }
        val keyFile = layout.vaultKey.readBytes()

        assertEquals(PasswordCheck.Rejected, runBlocking { checkAppPassword(holder, DURESS.toCharArray()) })
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        assertEquals(FailureCapOutcome.Rejected, runBlocking { saveFailureLimits(holder, DURESS.toCharArray(), 10_000L, 5) })
        clock.advance(FailureDelay.MAX_CAP_MS + 1)

        // The real vault is as it was: same key file, no marker, and the App Password still opens its wallet.
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        assertEquals(PasswordCheck.Accepted, runBlocking { checkAppPassword(holder, APP.toCharArray()) })
        holder.lock()
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val reopened = open(APP)
        assertEquals(0, reopened.side)
        assertEquals(listOf(realWallet), reopened.listWallets())
        reopened.close()
    }

    @Test
    fun aCheckThatItsCallerCancelledGivesTheCancellationNotAnAcceptance() {
        runBlocking { holder.adopt(open(APP), holder.unlockEpoch()) }
        var result: PasswordCheck? = null
        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) {
                coroutineContext.job.cancel()
                result = checkAppPassword(holder, APP.toCharArray())
            }.join()
        }

        assertNull(result)
    }

    private fun open(password: String) = runBlocking {
        (vault.unlock(password.toCharArray()) as UnlockResult.Unlocked).session
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
