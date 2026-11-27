package app.lensly.core.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.io.File
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The hardware key's device authentication. A key that needs one asks the owner once, after the password checks out. A cancel
 * stops the step, and a key that Android has deleted is reported as such. [MemoryKeystore] stands in for the Keystore.
 */
class VaultDeviceAuthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private var asked = 0
    private var answer = true
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        vault = Vault(VaultLayout(File(tmp.root, "vault")), keys, clock, KDF, authorize = {
            asked++
            if (answer) keys.hardwareAuthRequired = false
            answer
        })
    }

    @Test
    fun aStaleKeyAsksOnceAndThenOpensTheVault() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        keys.hardwareAuthRequired = true

        val opened = runBlocking { vault.unlock(APP.toCharArray()) }
        assertIs<UnlockResult.Unlocked>(opened)
        opened.session.close()
        assertEquals(1, asked)

        val again = runBlocking { vault.unlock(APP.toCharArray()) }
        assertIs<UnlockResult.Unlocked>(again)
        again.session.close()
        assertEquals(1, asked, "A fresh key is used without asking again")
    }

    @Test
    fun aWrongPasswordIsRejectedBeforeTheOwnerIsAsked() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        keys.hardwareAuthRequired = true

        assertEquals(UnlockResult.Rejected, runBlocking { vault.unlock(WRONG.toCharArray()) })
        assertEquals(0, asked)
    }

    @Test
    fun aCancelledAuthenticationStopsTheUnlock() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        keys.hardwareAuthRequired = true
        answer = false

        assertFailsWith<VaultAuthRequiredException> { runBlocking { vault.unlock(APP.toCharArray()) } }
        assertEquals(1, asked)
    }

    @Test
    fun aCreateAsksBeforeTheVaultKeyIsSealed() {
        keys.hardwareAuthRequired = true

        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        assertEquals(1, asked)
        assertIs<UnlockResult.Unlocked>(runBlocking { vault.unlock(APP.toCharArray()) }.also { closeIfOpen(it) })
    }

    @Test
    fun aDeletedKeyErasesTheVaultWithoutAsking() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        keys.hardwareKeyInvalidated = true

        assertEquals(UnlockResult.Erased, runBlocking { vault.unlock(APP.toCharArray()) })
        assertFalse(vault.exists())
        assertEquals(0, asked)
    }

    @Test
    fun aSlotKeyLostWhileTheNfcFormIsDerivedErasesTheVault() {
        val lossy = LosesSlotKeyOnSecondUse(keys)
        val own = Vault(VaultLayout(File(tmp.root, "nfc")), lossy, clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { own.create(APP.toCharArray()) })
        lossy.armed = true

        // A password that is not in NFC derives two keys, the typed text's first and then its NFC form's.
        assertEquals(UnlockResult.Erased, runBlocking { own.unlock(DECOMPOSED.toCharArray()) })
        assertFalse(own.exists())
    }

    private class LosesSlotKeyOnSecondUse(private val inner: MemoryKeystore) : KeystoreBackend by inner {
        var armed = false
        private var uses = 0

        override fun slotMac(message: ByteArray): ByteArray {
            if (armed && ++uses > 1) throw VaultKeyInvalidatedException("The vault's slot key is missing from the Keystore.")
            return inner.slotMac(message)
        }
    }

    private fun closeIfOpen(result: UnlockResult) {
        if (result is UnlockResult.Unlocked) result.session.close()
    }

    private companion object {
        const val APP = "TEST_ONLY_q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DECOMPOSED = "TEST_ONLY_e\u0301q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "TEST_ONLY_Zx8#Vb2!Nm5\$Qw7%Ld4"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}

/** The Keystore errors that the vault turns into an auth request or a lost key. Robolectric supplies the framework classes. */
@RunWith(RobolectricTestRunner::class)
class KeystoreFailureTest {

    @Test
    fun aMissingDeviceAuthenticationIsAnAuthRequest() {
        assertIs<VaultAuthRequiredException>(keystoreFailure("message", UserNotAuthenticatedException()))
    }

    @Test
    fun aPermanentlyInvalidatedKeyIsReportedAsLost() {
        assertIs<VaultKeyInvalidatedException>(keystoreFailure("message", KeyPermanentlyInvalidatedException()))
    }

    @Test
    fun anyOtherKeystoreErrorIsPlainVaultDamage() {
        val mapped = keystoreFailure("message", GeneralSecurityException("boom"))
        assertEquals(VaultException::class, mapped::class)
    }
}
