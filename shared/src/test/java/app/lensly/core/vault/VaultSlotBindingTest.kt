package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the slot key does. Every slot's software key is derived with the Keystore's slot key, so a password opens a slot only on the device
 * whose slot key sealed it. Each test here changes one thing under a vault (the slot key, the password, or the format of a file) and checks
 * what the vault does. [MemoryKeystore.createSlotKey] stands in for a device with another slot key.
 */
class VaultSlotBindingTest {

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
    }

    @Test
    fun theAppPasswordOpensTheRealVault() {
        val session = unlocked(APP)

        assertFalse(session.isDecoy)
        session.close()
    }

    @Test
    fun aWrongPasswordIsRejectedAndCounted() {
        assertEquals(UnlockResult.Rejected, attempt(WRONG))
        assertEquals(1, vault.failureCountForTesting())
    }

    @Test
    fun theAppSlotDoesNotOpenUnderAnotherSlotKeyEvenWithTheRightPassword() {
        unlocked(APP).close()

        keys.createSlotKey()

        assertEquals(UnlockResult.Rejected, attempt(APP))
    }

    @Test
    fun theDuressSlotDoesNotOpenUnderAnotherSlotKeyEvenWithTheRightPassword() {
        val real = unlocked(APP)
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()
        unlocked(DURESS).close()

        keys.createSlotKey()

        clock.advance(FailureDelay.LEARNING_CAP_MS)
        assertEquals(UnlockResult.Rejected, attempt(DURESS))
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        assertEquals(UnlockResult.Rejected, attempt(APP))
    }

    @Test
    fun anOlderHeaderIsRefusedBeforeAnyPasswordIsChecked() {
        // Version 1 is refused.
        patchByte(layout.header, index = 4, value = 1)

        assertTrue(vault.formatChanged())
        assertFailsWith<VaultFormatChangedException> { attempt(APP) }
        assertFailsWith<VaultFormatChangedException> { attempt(WRONG) }
        assertEquals(0, vault.failureCountForTesting())
    }

    @Test
    fun anOlderKeyFileIsRefusedAsAFormatChange() {
        // Version 2 is refused.
        patchByte(layout.vaultKey, index = 4, value = 2)

        assertFailsWith<VaultFormatChangedException> { attempt(APP) }
    }

    @Test
    fun aCurrentVaultIsNotAFormatChange() {
        assertFalse(vault.formatChanged())
    }

    private fun unlocked(password: String): VaultSession {
        val result = attempt(password)
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private fun attempt(password: String): UnlockResult = runBlocking { vault.unlock(password.toCharArray()) }

    private fun setDuress(session: VaultSession): DuressResult = runBlocking {
        vault.setDuress(session, DURESS.toCharArray()) { decoy -> decoy.createWallet() }
    }

    /** Overwrites one byte of [file], as an edited copy would leave it. */
    private fun patchByte(file: File, index: Int, value: Int) {
        val bytes = file.readBytes()
        bytes[index] = value.toByte()
        file.writeBytes(bytes)
    }

    private companion object {
        const val APP = "TEST_ONLY_q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "TEST_ONLY_Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val DURESS = "TEST_ONLY_Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
