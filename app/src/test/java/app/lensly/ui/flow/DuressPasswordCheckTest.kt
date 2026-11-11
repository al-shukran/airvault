package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.app.DecoyVault
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shared App Password check accepts only the password of the vault the open session belongs to. A decoy takes the
 * Duress Password, and the real App Password is refused there, and the reverse holds in the real vault.
 */
class DuressPasswordCheckTest {

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
            val real = (vault.unlock(APP.toCharArray()) as UnlockResult.Unlocked).session
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
    fun inTheDecoyOnlyTheDuressPasswordIsAccepted() {
        runBlocking { holder.adopt(open(DURESS), holder.unlockEpoch()) }

        assertEquals(PasswordCheck.Accepted, runBlocking { checkAppPassword(holder, DURESS.toCharArray()) })
        // The real App Password opens the real vault, but it is refused inside the decoy, so the decoy shows nothing of it.
        assertEquals(PasswordCheck.Rejected, runBlocking { checkAppPassword(holder, APP.toCharArray()) })
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
