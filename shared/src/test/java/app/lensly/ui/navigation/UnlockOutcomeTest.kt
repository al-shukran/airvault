package app.lensly.ui.navigation

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultLayout
import java.io.File
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where the gate sends the owner after an unlock, with or without a screen lock on the phone. A vault the Keystore reports as lost is erased inside
 * the unlock, so it leaves like any erase. An error the vault cannot explain shows the neutral Unavailable page, never advice about the
 * screen lock.
 */
class UnlockOutcomeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private var hardwareFails = false
    private var lockTheHolderOnTheNextKey = false
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder

    @Before
    fun setUp() {
        val failing = object : KeystoreBackend by keys {
            override fun hardwareKey(): SecretKey {
                if (hardwareFails) throw VaultException("The Keystore refused the hardware key.")
                // The vault is locked while the unlock runs, as a lock after the session went to the background does.
                if (lockTheHolderOnTheNextKey) holder.lock()
                return keys.hardwareKey()
            }
        }
        vault = Vault(VaultLayout(File(tmp.root, "vault")), failing, FakeClock(), KDF)
        holder = VaultHolder(vault)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    @Test
    fun anErrorTheVaultCannotExplainShowsTheUnavailablePage() {
        hardwareFails = true

        assertEquals(GateOutcome.Unavailable, runBlocking { unlockWith(holder, APP.toCharArray()) })
    }

    @Test
    fun aKeyAndroidDeletedErasesTheVaultAndLeavesLikeAnyErase() {
        keys.hardwareKeyInvalidated = true

        assertEquals(GateOutcome.Erased, runBlocking { unlockWith(holder, APP.toCharArray()) })
    }

    @Test
    fun aDuressUnlockThatThePromotionCommittedButTheHolderDroppedIsNotShownAsAWrongPassword() = runBlocking {
        val session = (vault.unlock(APP.toCharArray()) as UnlockResult.Unlocked).session
        assertEquals(DuressResult.Enabled, vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() })
        session.close()
        lockTheHolderOnTheNextKey = true

        // The old password is gone from this commit on, so this is no wrong password: nothing is counted, and the same password opens next.
        assertEquals(GateOutcome.Cancelled, unlockWith(holder, DURESS.toCharArray()))

        lockTheHolderOnTheNextKey = false
        assertEquals(GateOutcome.Opened, unlockWith(holder, DURESS.toCharArray()))
        holder.lock()
    }

    private companion object {
        const val APP = "TEST_ONLY_q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "TEST_ONLY_Hj3\$kP9wQz!Lm6Tn2Ysd"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
