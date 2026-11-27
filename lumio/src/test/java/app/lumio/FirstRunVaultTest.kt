package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.navigation.GateOutcome
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The first run's App Password step makes the vault once. A second try, as after a rotation mid-way, sends the owner to the gate. */
@RunWith(RobolectricTestRunner::class)
class FirstRunVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }
    private val holder by lazy { VaultHolder(vault) }

    @Test
    fun theFirstTryMakesAndOpensTheVault(): Unit = runBlocking {
        assertEquals(GateOutcome.Opened, createFirstVault(holder, FIRST.toCharArray()))
        assertNotNull(holder.openSession())
    }

    @Test
    fun aSecondTryLeavesTheFirstVaultAndZeroesItsPassword(): Unit = runBlocking {
        assertEquals(CreateResult.Created, vault.create(FIRST.toCharArray()))
        val second = SECOND.toCharArray()

        assertNull(createFirstVault(holder, second))

        assertEquals(CharArray(second.size).concatToString(), second.concatToString())
        clock.advance(ONE_DAY_MS)
        assertIs<UnlockResult.Unlocked>(vault.unlock(FIRST.toCharArray())).session.close()
    }

    private companion object {
        const val FIRST = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val SECOND = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
