package app.lensly.core.vault

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
        session.close()

        runBlocking { unlock.disable() }

        assertFalse(unlock.isOn())
        assertFalse(biometric.keyMade)
    }

    @Test
    fun aWipeRemovesTheSealedKey() {
        val session = openApp()
        assertTrue(runBlocking { unlock.enable(session) })
        session.close()

        vault.wipe()

        assertFalse(unlock.isOn())
    }

    private fun openApp(): VaultSession = assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(APP.toCharArray()) }).session

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
        const val DURESS = "Hj3\$kP9wQz!Lm6Tn2Ysd"
    }
}
