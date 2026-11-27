package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Changing the password from inside the Decoy Vault must not test guesses of the real App Password for free. Every such change is one
 * counted try of the real vault, never refunded, and the answer does not say whether the guess was the real password.
 */
class VaultDecoyChangeOracleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, keys, clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        val real = unlocked(APP)
        real.createWallet()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()
    }

    @Test
    fun everyChangeFromTheDecoyCountsAsOneWrongTryOfTheRealVault() {
        val decoy = unlocked(DURESS)
        assertEquals(0, vault.failureCountForTesting())

        assertEquals(CreateResult.Created, change(decoy, guess(1)))
        assertEquals(1, vault.failureCountForTesting())
        assertEquals(CreateResult.Created, change(decoy, guess(2)))
        assertEquals(2, vault.failureCountForTesting())
        decoy.close()
    }

    @Test
    fun theCountIsKeptWhenTheDecoyIsOpenedAgainAndNoChangeIsRefunded() {
        var decoy = unlocked(DURESS)
        assertEquals(CreateResult.Created, change(decoy, guess(1)))
        decoy.close()

        decoy = unlocked(guess(1))
        assertTrue(decoy.isDecoy)
        decoy.close()

        assertEquals(1, vault.failureCountForTesting())
    }

    @Test
    fun theChangesEraseTheVaultAtTheSameCountAsWrongPasswordsAtTheGate() {
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), null, 5) })
        val decoy = unlocked(DURESS)

        repeat(4) { assertEquals(CreateResult.Created, change(decoy, guess(it))) }
        assertTrue(vault.exists())
        assertEquals(CreateResult.Locked, change(decoy, guess(9)))

        assertFalse(vault.exists())
        assertFalse(layout.root.exists())
    }

    @Test
    fun theLastAllowedTryErasesEvenWhenItIsTheRealPassword() {
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), null, 3) })
        val decoy = unlocked(DURESS)

        repeat(2) { assertEquals(CreateResult.Created, change(decoy, guess(it))) }
        assertEquals(CreateResult.Locked, change(decoy, APP))

        assertFalse(vault.exists())
    }

    @Test
    fun aChangeIsRefusedWhileAFailureDelayRuns() {
        val decoy = unlocked(DURESS)
        // Enough counted tries that a wait follows the last one.
        repeat(6) { assertEquals(CreateResult.Created, change(decoy, guess(it))) }
        val before = vault.failureCountForTesting()

        assertEquals(CreateResult.Locked, runBlocking { vault.rekey(decoy, guess(7).toCharArray()) })

        assertEquals(before, vault.failureCountForTesting())
        decoy.close()
    }

    @Test
    fun theRealPasswordGetsTheSameAnswerAsAnyOtherAndChangesNothing() {
        val decoy = unlocked(DURESS)

        val other = change(decoy, guess(1))
        val real = change(decoy, APP)
        decoy.close()

        assertEquals(other, real)
        assertEquals(CreateResult.Created, real)
        // The real password did not become the decoy's, and the real vault still opens with it.
        assertFalse(unlocked(APP).also { it.close() }.isDecoy)
        // The earlier guess is the decoy's password now, the real password's change left it alone.
        assertTrue(unlocked(guess(1)).also { it.close() }.isDecoy)
    }

    @Test
    fun theKeyFileKeepsItsSizeWhetherOrNotTheGuessWasTheRealPassword() {
        val decoy = unlocked(DURESS)
        change(decoy, guess(1))
        val missSize = layout.vaultKey.length()
        change(decoy, APP)
        assertEquals(missSize, layout.vaultKey.length())
        decoy.close()
    }

    private fun guess(n: Int) = "Zx8#Vb2!Nm5\$Qw7%Ld4-$n"

    private fun change(decoy: VaultSession, password: String): CreateResult {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return runBlocking { vault.rekey(decoy, password.toCharArray()) }
    }

    private fun unlocked(password: String): VaultSession {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
