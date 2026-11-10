package app.lensly.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = FakeClock()
    private val vault = Vault(context, clock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))
    private val vaultRoot = File(context.noBackupFilesDir, "vault")
    private val failures = File(vaultRoot, "failures.bin")
    private val failuresBackup = File(vaultRoot, "failures.bak")

    @Before
    fun startFromNoVault() {
        vault.wipe()
    }

    @After
    fun removeVault() {
        vault.wipe()
    }

    @Test
    fun createThenUnlockSucceeds() {
        runBlocking {
            assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
            assertTrue(vault.exists())

            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))
            assertEquals(emptyList<String>(), session.listWallets())
            session.close()
        }
    }

    @Test
    fun wrongPasswordIsRejectedAndIncrementsTheCount() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            assertRejected(vault.unlock(WRONG.toCharArray()))
            assertEquals(1, vault.failureCountForTesting())
        }
    }

    @Test
    fun correctPasswordIsRejectedWhileTheDelayRuns() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            vault.unlock(WRONG.toCharArray())

            assertRejected(vault.unlock(PASSWORD.toCharArray()))
            assertEquals(1, vault.failureCountForTesting()) // A refused attempt does not count as a failure.

            clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
        }
    }

    @Test
    fun correctPasswordUnlocksAfterTheDelayAndResetsTheCount() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            vault.unlock(WRONG.toCharArray())

            clock.advance(FailureDelay.LEARNING_CAP_MS)
            unlocked(vault.unlock(PASSWORD.toCharArray())).close()
            assertEquals(0, vault.failureCountForTesting())
        }
    }

    @Test
    fun blockedAttemptSpendsTheSameKdfTimeAsARealCheck() {
        runBlocking {
            // A costly KDF makes the difference easy to measure. Without the fix, a blocked attempt returns in microseconds.
            val costly = Vault(context, clock, KdfParams(memoryKiB = 64 * 1024, iterations = 3))
            costly.create(PASSWORD.toCharArray())

            val realCheckMs = elapsedMs { assertRejected(costly.unlock(WRONG.toCharArray())) }
            assertEquals(1, costly.failureCountForTesting())
            val blockedMs = elapsedMs { assertRejected(costly.unlock(PASSWORD.toCharArray())) }
            assertEquals(1, costly.failureCountForTesting())

            assertTrue(
                "A blocked attempt took $blockedMs ms, but a real check took $realCheckMs ms.",
                blockedMs * 2 >= realCheckMs,
            )
        }
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    @Test
    fun rekeyReplacesThePasswordAndKeepsRecordsReadable() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))
            val wallet = session.createWallet()
            session.putRecord(wallet, "seed", byteArrayOf(1, 2, 3, 4))

            assertEquals(CreateResult.Created, vault.rekey(session, OTHER.toCharArray()))

            assertRejected(vault.unlock(PASSWORD.toCharArray()))
            clock.advance(FailureDelay.LEARNING_CAP_MS)
            val reopened = unlocked(vault.unlock(OTHER.toCharArray()))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.getRecord(wallet, "seed"))

            reopened.close()
            session.close()
        }
    }

    @Test
    fun rekeyRefusesAWeakPasswordAndKeepsTheOldOne() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))

            assertEquals(
                CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE),
                vault.rekey(session, "password1234".toCharArray()),
            )
            unlocked(vault.unlock(PASSWORD.toCharArray())).close()
            session.close()
        }
    }

    @Test
    fun deleteWalletMakesItsRecordUnreadable() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))
            val wallet = session.createWallet()
            session.putRecord(wallet, "seed", byteArrayOf(9, 9, 9))
            assertNotNull(session.getRecord(wallet, "seed"))

            session.deleteWallet(wallet)
            assertNull(session.getRecord(wallet, "seed"))
            assertFalse(session.listWallets().contains(wallet))
            assertFalse(File(vaultRoot, "wallets/$wallet").exists())
            session.close()
        }
    }

    @Test
    fun restoredCiphertextIsUnreadableAfterDeleteWallet() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))
            val wallet = session.createWallet()
            session.putRecord(wallet, "seed", byteArrayOf(5, 6, 7))
            val recordFile = File(vaultRoot, "wallets/$wallet/seed.bin")
            val ciphertext = recordFile.readBytes()

            session.deleteWallet(wallet)
            // Put the old ciphertext back on its own. Without the wallet key it must not decrypt.
            File(vaultRoot, "wallets/$wallet").mkdirs()
            recordFile.writeBytes(ciphertext)
            assertNull(session.getRecord(wallet, "seed"))
            session.close()
        }
    }

    @Test
    fun tamperedFailureFileLocksForTheCapEvenWithTheCorrectPassword() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            vault.unlock(WRONG.toCharArray()) // One failure, so a 10 s delay.

            val bytes = failures.readBytes()
            bytes[10] = (bytes[10].toInt() xor 1).toByte()
            failures.writeBytes(bytes)

            // The tampering is detected on this attempt, and the cap applies from now.
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
            clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
            clock.advance(1)
            unlocked(vault.unlock(PASSWORD.toCharArray())).close()
        }
    }

    @Test
    fun rollingBackTheFailureFilesIsDetectedWhileTheProcessIsAlive() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val olderPrimary = failures.readBytes()
            val olderBackup = failuresBackup.readBytes()
            vault.unlock(WRONG.toCharArray()) // Writes sequence 2.
            clock.advance(FailureDelay.LEARNING_CAP_MS)

            failures.writeBytes(olderPrimary)
            failuresBackup.writeBytes(olderBackup)
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
            clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
            clock.advance(1)
            unlocked(vault.unlock(PASSWORD.toCharArray())).close()
        }
    }

    @Test
    fun weakPasswordIsRefusedAtCreation() {
        runBlocking {
            assertEquals(
                CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE),
                vault.create("password1234".toCharArray()),
            )
            assertFalse(vault.exists())
        }
    }

    @Test
    fun unlockWithoutAVaultIsRejected() {
        runBlocking {
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
        }
    }

    @Test
    fun createRefusesToOverwriteAnExistingVault() {
        runBlocking { vault.create(PASSWORD.toCharArray()) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { vault.create(OTHER.toCharArray()) }
        }
    }

    @Test
    fun closedSessionRejectsFurtherUse() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))
            session.close()
            assertThrows(IllegalStateException::class.java) { session.listWallets() }
        }
    }

    @Test
    fun recordNamesAndWalletIdsAreValidated() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val session = unlocked(vault.unlock(PASSWORD.toCharArray()))
            val wallet = session.createWallet()

            assertThrows(IllegalArgumentException::class.java) { session.putRecord(wallet, "../escape", byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { session.putRecord(wallet, "pwk", byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { session.getRecord("not-a-wallet-id", "seed") }
            assertThrows(IllegalArgumentException::class.java) {
                session.putRecord("00000000-0000-0000-0000-000000000000", "seed", byteArrayOf(1))
            }
            session.close()
        }
    }

    @Test
    fun walletsAndRecordsSurviveReopeningTheVault() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            val first = unlocked(vault.unlock(PASSWORD.toCharArray()))
            val wallet = first.createWallet()
            first.putRecord(wallet, "notes", "hello".toByteArray())
            first.close()

            val reopened = unlocked(vault.unlock(PASSWORD.toCharArray()))
            assertEquals(listOf(wallet), reopened.listWallets())
            assertArrayEquals("hello".toByteArray(), reopened.getRecord(wallet, "notes"))
            reopened.close()
        }
    }

    @Test
    fun deviceSecurityIsTheSameBeforeAndAfterCreation() {
        runBlocking {
            val probed = vault.deviceSecurity()
            vault.create(PASSWORD.toCharArray())
            assertEquals(probed, vault.deviceSecurity())
        }
    }

    @Test
    fun wipeRemovesTheVaultAndItsKeys() {
        runBlocking {
            vault.create(PASSWORD.toCharArray())
            vault.wipe()
            assertFalse(vault.exists())
            assertFalse(vaultRoot.exists())
            assertRejected(vault.unlock(PASSWORD.toCharArray()))
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
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val OTHER = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
    }
}
