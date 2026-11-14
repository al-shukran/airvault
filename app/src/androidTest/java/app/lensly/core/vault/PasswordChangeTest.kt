package app.lensly.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An App Password change on the device. Rekey re-wraps the vault key under the new password. So the new password opens
 * the vault, the old one does not, and every record is still there and unchanged. A change that stops before its commit
 * leaves the old key file in place, so one of the two passwords still opens the vault.
 */
@RunWith(AndroidJUnit4::class)
class PasswordChangeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = FakeClock()
    private val vault = Vault(context, clock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))
    private val vaultRoot = File(context.noBackupFilesDir, "vault")
    private val vaultKeyFile = File(vaultRoot, "vault.key")
    private val pendingKeyFile = File(vaultRoot, "vault.key.tmp")

    @Before
    fun startFromNoVault() {
        vault.wipe()
    }

    @After
    fun removeVault() {
        vault.wipe()
    }

    @Test
    fun theNewPasswordOpensTheVaultAndTheOldOneDoesNot() {
        runBlocking {
            vault.create(OLD.toCharArray())
            val session = unlocked(vault.unlock(OLD.toCharArray()))
            session.createWallet()

            assertEquals(CreateResult.Created, vault.rekey(session, NEW.toCharArray()))
            session.close()

            assertRejected(vault.unlock(OLD.toCharArray()))
            clock.advance(FailureDelay.LEARNING_CAP_MS)
            unlocked(vault.unlock(NEW.toCharArray())).close()
        }
    }

    @Test
    fun everyRecordIsIntactAfterTheChangeAndNoneIsReEncrypted() {
        runBlocking {
            vault.create(OLD.toCharArray())
            val session = unlocked(vault.unlock(OLD.toCharArray()))
            val wallet = session.createWallet()
            session.putRecord(wallet, "secret", SEED)
            session.putRecord(wallet, "profile", PROFILE)
            session.putVaultRecord("settings", SETTINGS)
            val walletKeyBefore = File(vaultRoot, "wallets/$wallet/pwk.bin").readBytes()
            val seedBefore = File(vaultRoot, "wallets/$wallet/seed.bin").readBytes()

            assertEquals(CreateResult.Created, vault.rekey(session, NEW.toCharArray()))
            session.close()

            // Only the vault key file changed. The per-wallet key and the record files are the same bytes.
            assertArrayEquals(walletKeyBefore, File(vaultRoot, "wallets/$wallet/pwk.bin").readBytes())
            assertArrayEquals(seedBefore, File(vaultRoot, "wallets/$wallet/seed.bin").readBytes())

            clock.advance(FailureDelay.LEARNING_CAP_MS)
            val reopened = unlocked(vault.unlock(NEW.toCharArray()))
            assertEquals(listOf(wallet), reopened.listWallets())
            assertArrayEquals(SEED, reopened.getRecord(wallet, "secret"))
            assertArrayEquals(PROFILE, reopened.getRecord(wallet, "profile"))
            assertArrayEquals(SETTINGS, reopened.getVaultRecord("settings"))
            reopened.close()
        }
    }

    @Test
    fun aChangeStoppedBeforeItsCommitKeepsTheOldPasswordWorking() {
        runBlocking {
            vault.create(OLD.toCharArray())
            val session = unlocked(vault.unlock(OLD.toCharArray()))
            val wallet = session.createWallet()
            session.putRecord(wallet, "secret", SEED)
            val oldKeyFile = vaultKeyFile.readBytes()

            // Run the whole change, then put the files back to the moment before the rename. The new key file is
            // complete and verified, but it is still the pending file, and the old key file is the one in place.
            assertEquals(CreateResult.Created, vault.rekey(session, NEW.toCharArray()))
            session.close()
            pendingKeyFile.writeBytes(vaultKeyFile.readBytes())
            vaultKeyFile.writeBytes(oldKeyFile)

            assertRejected(vault.unlock(NEW.toCharArray()))
            clock.advance(FailureDelay.LEARNING_CAP_MS)
            val reopened = unlocked(vault.unlock(OLD.toCharArray()))
            assertArrayEquals(SEED, reopened.getRecord(wallet, "secret"))
            reopened.close()
        }
    }

    @Test
    fun aChangeStoppedWhileItWritesTheNewKeyFileKeepsTheOldPasswordWorking() {
        runBlocking {
            vault.create(OLD.toCharArray())
            val session = unlocked(vault.unlock(OLD.toCharArray()))
            val wallet = session.createWallet()
            session.putRecord(wallet, "secret", SEED)

            // The process died part way through writing the pending key file. The vault key file was never touched.
            pendingKeyFile.writeBytes(ByteArray(40) { 7 })
            val stillOpen = unlocked(vault.unlock(OLD.toCharArray()))
            assertArrayEquals(SEED, stillOpen.getRecord(wallet, "secret"))
            stillOpen.close()

            // A retry replaces the partial file, commits, and leaves no pending file behind.
            assertEquals(CreateResult.Created, vault.rekey(session, NEW.toCharArray()))
            session.close()
            assertFalse(pendingKeyFile.exists())
            clock.advance(FailureDelay.LEARNING_CAP_MS)
            unlocked(vault.unlock(NEW.toCharArray())).close()
            assertTrue(vault.exists())
        }
    }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertTrue("Expected Unlocked but was $result", result is UnlockResult.Unlocked)
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertTrue("Expected Rejected but was $result", result is UnlockResult.Rejected)
    }

    private companion object {
        const val OLD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val NEW = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        val SEED = byteArrayOf(1, 2, 3, 4, 5)
        val PROFILE = "profile".toByteArray()
        val SETTINGS = byteArrayOf(9, 8, 7)
    }
}
