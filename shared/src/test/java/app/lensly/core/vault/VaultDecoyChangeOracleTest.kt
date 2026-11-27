package app.lensly.core.vault

import java.io.File
import kotlin.test.assertContentEquals
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
    private var derivations = 0
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        build(APP)
    }

    /** A vault with a decoy, in its own folder. Every Argon2 derivation of it asks the slot key once, so [derivations] counts them. */
    private fun build(realPassword: String) {
        layout = VaultLayout(File(tmp.root, "vault-${tmp.root.list().orEmpty().size}"))
        val counting = object : KeystoreBackend by keys {
            override fun slotMac(message: ByteArray): ByteArray {
                derivations++
                return keys.slotMac(message)
            }
        }
        vault = Vault(layout, counting, clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(realPassword.toCharArray()) })
        val real = unlocked(realPassword)
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
    fun aLockedChangeDuringADelayWritesNothing() {
        val decoy = unlocked(DURESS)
        repeat(6) { assertEquals(CreateResult.Created, change(decoy, guess(it))) }
        val stateBefore = failureState()
        val keyFileBefore = layout.vaultKey.readBytes()
        val filesBefore = filesUnderRoot()

        assertEquals(CreateResult.Locked, runBlocking { vault.rekey(decoy, guess(7).toCharArray()) })

        // The only write is the time the wait has served, which a blocked try at the gate saves too. No try is counted.
        assertEquals(stateBefore.copy(seq = 0), failureState().copy(seq = 0))
        assertContentEquals(keyFileBefore, layout.vaultKey.readBytes())
        assertEquals(filesBefore, filesUnderRoot())
        decoy.close()
    }

    @Test
    fun aHitAndAMissWriteTheFailureStateTheSameWay() {
        val decoy = unlocked(DURESS)
        val start = failureState()

        change(decoy, guess(1))
        val afterMiss = failureState()
        change(decoy, APP)
        val afterHit = failureState()
        change(decoy, guess(2))
        val afterSecondMiss = failureState()
        decoy.close()

        // One counted try and one write each, whether or not the guess was the real password.
        assertEquals(1, afterMiss.failures - start.failures)
        assertEquals(1, afterHit.failures - afterMiss.failures)
        assertEquals(1, afterSecondMiss.failures - afterHit.failures)
        assertEquals(afterMiss.seq - start.seq, afterHit.seq - afterMiss.seq)
        assertEquals(afterHit.seq - afterMiss.seq, afterSecondMiss.seq - afterHit.seq)
        assertEquals(afterMiss.copy(seq = 0, failures = 0, lastFailureWallMs = 0, lastFailureElapsedMs = 0, elapsedAtSave = 0),
            afterHit.copy(seq = 0, failures = 0, lastFailureWallMs = 0, lastFailureElapsedMs = 0, elapsedAtSave = 0))
    }

    @Test
    fun aHitCostsTheSameDerivationsAsAMissForAnAsciiPassword() {
        val decoy = unlocked(DURESS)

        assertEquals(derivationsOf { change(decoy, guess(1)) }, derivationsOf { change(decoy, APP) })
        decoy.close()
    }

    @Test
    fun aHitCostsTheSameDerivationsAsAMissWhateverFormOfANonAsciiPasswordIsTyped() {
        build(APP_NFC)
        val decoy = unlocked(DURESS)

        assertEquals(derivationsOf { change(decoy, WRONG_NFC) }, derivationsOf { change(decoy, APP_NFC) })
        assertEquals(derivationsOf { change(decoy, WRONG_NFD) }, derivationsOf { change(decoy, APP_NFD) })
        decoy.close()
    }

    @Test
    fun theNfdFormOfTheRealPasswordGetsTheSameAnswerAndChangesNothing() {
        build(APP_NFC)
        val decoy = unlocked(DURESS)
        val keyFileBefore = layout.vaultKey.readBytes()

        assertEquals(CreateResult.Created, change(decoy, APP_NFD))
        decoy.close()

        assertEquals(1, vault.failureCountForTesting())
        // The key file keeps its size, and both slots still open only with their own password.
        assertEquals(keyFileBefore.size, layout.vaultKey.length().toInt())
        assertFalse(unlocked(APP_NFC).also { it.close() }.isDecoy)
        assertTrue(unlocked(DURESS).also { it.close() }.isDecoy)
        assertFalse(unlocked(APP_NFD).also { it.close() }.isDecoy)
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

    private fun failureState(): FailureState = FailureStore(layout.root, keys).load(clock)

    private fun filesUnderRoot(): Map<String, Long> =
        layout.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(layout.root).path to it.length() }

    /** The slot-key calls that [block] makes, which is its Argon2 derivations. */
    private fun derivationsOf(block: () -> Unit): Int {
        val before = derivations
        block()
        return derivations - before
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
        const val APP_NFC = "Qx7!Rb9Tm4Wc8Zp-Caf\u00e9"
        const val APP_NFD = "Qx7!Rb9Tm4Wc8Zp-Cafe\u0301"
        const val WRONG_NFC = "Zx8#Vb2!Nm5\$Qw7-Th\u00e9"
        const val WRONG_NFD = "Zx8#Vb2!Nm5\$Qw7-The\u0301"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
