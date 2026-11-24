package app.lensly.core.vault

import app.lensly.app.VaultHolder
import app.lensly.ui.flow.BiometricEnableAppPorts
import app.lensly.ui.flow.BiometricEnableFlow
import app.lensly.ui.flow.BiometricEnableOutcome
import app.lensly.ui.flow.BiometricEnableStep
import java.io.File
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The state changes of biometric unlock: on, unlock, invalidation turns it off, and off. The vault is real, on an in-memory Keystore. The
 * biometric key and its prompt are [FakeBiometric].
 */
class BiometricUnlockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault
    private lateinit var biometric: FakeBiometric
    private lateinit var unlock: BiometricUnlock

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        biometric = FakeBiometric()
        unlock = BiometricUnlock(File(layout.root, BiometricUnlock.BLOB_FILE_NAME), biometric)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    @Test
    fun enablingThenUnlockingOpensTheSameRealVaultAndThePasswordStillWorks() {
        val session = openApp()
        val wallet = session.createWallet()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()

        val opened = assertIs<BiometricOutcome.Unlocked>(runBlocking { unlock.unlock(vault) }).session

        assertFalse(opened.isDecoy)
        assertEquals(listOf(wallet), opened.listWallets())
        opened.close()
        assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(APP.toCharArray()) }).session.close()
    }

    @Test
    fun theSealedFileDoesNotHoldTheVaultKeyInTheClear() {
        val session = openApp()
        val vaultKey = session.copyOfVaultKey()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()

        val sealed = File(layout.root, BiometricUnlock.BLOB_FILE_NAME).readBytes()

        assertFalse(sealed.holds(vaultKey))
    }

    @Test
    fun aCancelledEnableLeavesItOffWithNoKey() {
        biometric.passes = false
        val session = openApp()

        assertFalse(runBlocking { unlock.enable(session) })
        session.close()

        assertFalse(unlock.isOn())
        assertFalse(biometric.keyMade)
    }

    @Test
    fun enableNeedsAStrongBiometricEnrolled() {
        biometric.canUse = false
        val session = openApp()

        assertFalse(unlock.canEnable())
        assertFalse(runBlocking { unlock.enable(session) })
        session.close()

        assertFalse(unlock.isOn())
        assertFalse(biometric.keyMade)
    }

    @Test
    fun aDuressSessionNeverTurnsItOn() {
        val real = openApp()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { decoy -> decoy.createWallet() } })
        real.close()
        val decoy = assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(DURESS.toCharArray()) }).session
        assertTrue(decoy.isDecoy)

        assertFalse(runBlocking { unlock.enable(decoy) })
        decoy.close()

        assertFalse(unlock.isOn())
    }

    @Test
    fun aDeclinedUnlockKeepsItOn() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()
        biometric.passes = false

        assertEquals(BiometricOutcome.Declined, runBlocking { unlock.unlock(vault) })

        assertTrue(unlock.isOn())
    }

    @Test
    fun anInvalidatedKeyTurnsItOffAndDeletesTheSealedKey() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()
        biometric.failure = GeneralSecurityException("The key was invalidated by a new enrollment.")

        assertEquals(BiometricOutcome.TurnedOff, runBlocking { unlock.unlock(vault) })

        assertFalse(unlock.isOn())
        assertFalse(biometric.keyMade)
        assertEquals(BiometricOutcome.Declined, runBlocking { unlock.unlock(vault) })
    }

    @Test
    fun aSealedKeyThatDoesNotNameThisVaultTurnsItOff() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()
        biometric.substitute = ByteArray(32) { 7 }

        assertEquals(BiometricOutcome.TurnedOff, runBlocking { unlock.unlock(vault) })

        assertFalse(unlock.isOn())
    }

    @Test
    fun disablingDeletesTheSealedKeyAndTheBiometricKey() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })

        assertTrue(runBlocking { unlock.disable(session) })
        session.close()

        assertFalse(unlock.isOn())
        assertFalse(biometric.keyMade)
    }

    @Test
    fun aDuressSessionCannotTurnItOffAndTheRealSealedKeyStaysUsable() {
        val real = openApp()
        assertTrue(runBlocking { unlock.enable(real) })
        val decoy = openDuress(real)

        assertFalse(runBlocking { unlock.disable(decoy) })
        decoy.close()

        assertTrue(unlock.isOn())
        assertTrue(biometric.keyMade)
        assertIs<BiometricOutcome.Unlocked>(runBlocking { unlock.unlock(vault) }).session.close()
    }

    @Test
    fun aDuressSessionShowsItOffWhileTheRealVaultHasItOn() {
        val real = openApp()
        assertTrue(runBlocking { unlock.enable(real) })
        val decoy = openDuress(real)

        assertTrue(unlock.isOn())
        assertFalse(unlock.isOn(decoy))
        decoy.close()
    }

    @Test
    fun aWipeRemovesTheSealedKey() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()

        vault.wipe()

        assertFalse(unlock.isOn())
    }

    @Test
    fun aBiometricUnlockClearsTheWrongPasswordCount() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()
        assertEquals(UnlockResult.Rejected, runBlocking { vault.unlock(WRONG.toCharArray()) })
        assertEquals(1, vault.failureCountForTesting())

        assertIs<BiometricOutcome.Unlocked>(runBlocking { unlock.unlock(vault) }).session.close()

        assertEquals(0, vault.failureCountForTesting())
    }

    @Test
    fun aWrongAppPasswordSealsNothing() {
        val holder = VaultHolder(vault)
        assertTrue(holder.adopt(openApp(), holder.unlockEpoch()))
        val flow = BiometricEnableFlow(BiometricEnableAppPorts(holder, unlock))

        runBlocking { flow.submitPassword(WRONG.toCharArray()) }

        assertEquals(BiometricEnableStep.Password(busy = false), flow.state.value)
        assertFalse(unlock.isOn())
        assertFalse(biometric.keyMade)
    }

    @Test
    fun theRightAppPasswordThenRunsThePromptAndTurnsItOn() {
        val holder = VaultHolder(vault)
        assertTrue(holder.adopt(openApp(), holder.unlockEpoch()))
        val flow = BiometricEnableFlow(BiometricEnableAppPorts(holder, unlock))

        runBlocking { flow.submitPassword(APP.toCharArray()) }

        assertEquals(BiometricEnableStep.Ended(BiometricEnableOutcome.ON), flow.state.value)
        assertTrue(unlock.isOn())
    }

    /**
     * Change Password re-wraps the password's slot around the same Vault Key, so the sealed key still opens this vault after the change, and the
     * old password stops working. The biometric blob is not rewritten by the change.
     */
    @Test
    fun aPasswordChangeKeepsBiometricUnlockWorking() {
        val session = openApp()
        val vaultKey = session.copyOfVaultKey()
        assertTrue(runBlocking { unlock.enable(session) })
        val sealedBefore = File(layout.root, BiometricUnlock.BLOB_FILE_NAME).readBytes()

        assertEquals(CreateResult.Created, runBlocking { vault.rekey(session, CHANGED.toCharArray()) })
        session.close()

        assertTrue(File(layout.root, BiometricUnlock.BLOB_FILE_NAME).readBytes().contentEquals(sealedBefore))
        val opened = assertIs<BiometricOutcome.Unlocked>(runBlocking { unlock.unlock(vault) }).session
        assertTrue(opened.copyOfVaultKey().contentEquals(vaultKey))
        opened.close()
        assertEquals(UnlockResult.Rejected, runBlocking { vault.unlock(APP.toCharArray()) })
        // The wrong try starts a failure delay, which the next try has to wait out.
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(CHANGED.toCharArray()) }).session.close()
    }

    private fun openApp(): VaultSession = assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(APP.toCharArray()) }).session

    /** Sets a Duress Password on [real], closes it, and opens the Decoy Vault with the Duress Password. */
    private fun openDuress(real: VaultSession): VaultSession {
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { decoy -> decoy.createWallet() } })
        real.close()
        return assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(DURESS.toCharArray()) }).session.also { assertTrue(it.isDecoy) }
    }

    private fun ByteArray.holds(part: ByteArray): Boolean = (0..size - part.size).any { start ->
        part.indices.all { this[start + it] == part[it] }
    }

    /** Stands in for the biometric key: a fixed pad in place of the Keystore cipher, and a prompt that passes or fails as the test says. */
    private class FakeBiometric : BiometricCrypto {
        var canUse = true
        var passes = true
        var keyMade = false
        var failure: Exception? = null
        var substitute: ByteArray? = null
        private val pad = ByteArray(64) { (it * 37 + 11).toByte() }

        override fun available(): Boolean = canUse

        override fun createKey(): Boolean {
            keyMade = true
            return true
        }

        override suspend fun wrap(plain: ByteArray): WrappedKey? {
            check(keyMade) { "No biometric key." }
            return if (passes) WrappedKey(ByteArray(12) { (it + 1).toByte() }, xor(plain)) else null
        }

        override suspend fun unwrap(wrapped: WrappedKey): ByteArray? {
            failure?.let { throw it }
            return if (passes) substitute ?: xor(wrapped.ciphertext) else null
        }

        override fun deleteKey() {
            keyMade = false
        }

        private fun xor(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor pad[it].toInt()).toByte() }
    }

    private companion object {
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "not-the-app-password-2"
        const val CHANGED = "Vk8#Rw2!Lq6Zn4Tp9Xb"
        const val DURESS ="Hj3\$kP9wQz!Lm6Tn2Ysd"
    }
}
