package app.lensly.core.vault

import java.io.File
import java.security.ProviderException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the header MAC does. A header that does not verify is unverifiable, so unlock erases the vault before it checks any password, with the
 * same wipe that an unverifiable failure state gets. The header MAC has its own Keystore key, so a changed slot key still shows as a wrong
 * password (see [VaultSlotBindingTest]).
 */
class VaultHeaderBindingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()

    @Test
    fun theUnchangedHeaderUnlocks() {
        val made = createdVault()

        assertIs<UnlockResult.Unlocked>(attempt(made.vault, APP)).session.close()
    }

    @Test
    fun aChangedByteInAnyHeaderFieldErasesTheVaultBeforeAnyPasswordIsChecked() {
        for ((field, offset) in CHANGEABLE_FIELDS) {
            for (password in listOf(APP, WRONG)) {
                val made = createdVault()
                flipByte(made.layout.header, offset)

                val which = if (password == APP) "right" else "wrong"
                assertEquals(UnlockResult.Erased, attempt(made.vault, password), "$field changed, $which password")
                assertFalse(made.layout.header.exists(), "$field changed, $which password")
            }
        }
    }

    @Test
    fun aHeaderOfTheOldLengthIsErasedAsUnverifiable() {
        // Before the MAC, header.bin held the fields alone. A vault with such a header cannot be verified, so it is erased.
        val made = createdVault()
        made.layout.header.writeBytes(made.layout.header.readBytes().copyOf(VaultHeader.FIELDS_BYTES))

        assertEquals(UnlockResult.Erased, attempt(made.vault, APP))
        assertFalse(made.layout.header.exists())
    }

    @Test
    fun aHeaderKeyThatIsGoneErasesTheVault() {
        val made = createdVault()
        made.keys.createHeaderKey()

        assertEquals(UnlockResult.Erased, attempt(made.vault, APP))
        assertFalse(made.layout.header.exists())
    }

    @Test
    fun aKeystoreFaultWhileTheHeaderIsCheckedErasesTheVault() {
        val made = createdVault()
        val faulty = object : KeystoreBackend by made.keys {
            override fun headerMac(message: ByteArray): ByteArray = throw ProviderException("the header key cannot be used")
        }

        assertEquals(UnlockResult.Erased, attempt(Vault(made.layout, faulty, clock, KDF), APP))
        assertFalse(made.layout.header.exists())
    }

    @Test
    fun aTamperedHeaderErasesTheVaultOnTheBiometricPathToo() {
        val made = createdVault()
        val session = assertIs<UnlockResult.Unlocked>(attempt(made.vault, APP)).session
        val vaultKey = session.copyOfVaultKey()
        session.close()
        assertNotNull(runBlocking { made.vault.unlockWithVaultKey(vaultKey) }).close()
        flipByte(made.layout.header, VaultHeader.FIELDS_BYTES)

        assertNull(runBlocking { made.vault.unlockWithVaultKey(vaultKey) })
        assertFalse(made.layout.header.exists())
    }

    /** A vault made under APP in a folder of its own, so each test changes one vault. */
    private fun createdVault(): Made {
        val layout = VaultLayout(File(tmp.newFolder(), "vault"))
        val keys = MemoryKeystore()
        val vault = Vault(layout, keys, clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        return Made(layout, keys, vault)
    }

    private fun attempt(vault: Vault, password: String): UnlockResult = runBlocking { vault.unlock(password.toCharArray()) }

    /** Flips the low bit of the byte at [index] of [file], as an edited copy would leave it. */
    private fun flipByte(file: File, index: Int) {
        setByte(file, index, file.readBytes()[index].toInt() xor 1)
    }

    private fun setByte(file: File, index: Int, value: Int) {
        val bytes = file.readBytes()
        bytes[index] = value.toByte()
        file.writeBytes(bytes)
    }

    private class Made(val layout: VaultLayout, val keys: MemoryKeystore, val vault: Vault)

    private companion object {
        const val APP = "TEST_ONLY_q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "TEST_ONLY_Zx8#Vb2!Nm5\$Qw7%Ld4"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)

        /** Where each field of `header.bin` starts (see [VaultHeader]). */
        val CHANGEABLE_FIELDS = listOf(
            "magic" to 0,
            "version" to 4,
            "memoryKiB" to 5,
            "iterations" to 9,
            "parallelism" to 13,
            "salt" to 17,
            "createdAtWallMs" to 33,
            "flags" to 41,
            "keySecurityLevel" to 42,
            "MAC" to VaultHeader.FIELDS_BYTES,
        )
    }
}
