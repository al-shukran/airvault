package app.lensly.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.app.VaultHolder
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.checkAppPassword
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Duress Password on the device, with the real Android Keystore and the real files: unlocking with it erases the real vault and
 * promotes the decoy, and the new vault can take a Duress Password of its own, which promotes in turn. A password prompt that only checks
 * the App Password (Gallery, Show Secret) refuses the Duress Password and changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class DuressPromotionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = FakeClock()
    private val vault = instrumentedVault(context, clock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))
    private val layout = VaultLayout.of(context)

    @Before
    fun startFromNoVault() {
        vault.wipe()
    }

    @After
    fun removeVault() {
        vault.wipe()
    }

    @Test
    fun theDuressPasswordPromotesTheDecoyAndTheNewVaultCanTakeAnotherOne() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(A.toCharArray()) })
        val real = unlocked(A)
        val realWallet = real.createWallet()
        val realRecord = byteArrayOf(1, 2, 3)
        real.putRecord(realWallet, "secret", realRecord)
        val sealedSecret = layout.walletRecordFile(realWallet, "secret").readBytes()
        val firstDecoy = setDuress(real, B)
        real.close()
        val keyFileBefore = layout.vaultKey.readBytes()

        // A prompt that only checks the App Password refuses B, counts it as a wrong try and promotes nothing.
        assertRejected(verify(B))
        assertArrayEquals(keyFileBefore, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        val checked = unlocked(A)
        assertEquals(listOf(realWallet), checked.listWallets())
        checked.close()

        // Unlocking with B erases the real vault and makes the decoy the vault, on the side the decoy had.
        val promoted = unlocked(B)
        assertEquals(1, promoted.side)
        assertEquals(firstDecoy, promoted.listWallets())
        assertFalse("the old vault's wallet is gone", promoted.listWallets().contains(realWallet))
        assertFalse("the key file is the decoy's", layout.vaultKey.readBytes().contentEquals(keyFileBefore))
        awaitCleanup()
        assertFalse(layout.promotionMarker.exists())
        // The old wallet's record is gone or overwritten, so its sealed bytes are not on disk any more.
        val sealedFile = layout.walletRecordFile(realWallet, "secret")
        assertTrue("the old wallet's record is shredded", !sealedFile.exists() || !sealedFile.readBytes().contentEquals(sealedSecret))
        promoted.close()

        // A is refused. No Duress Password exists any more, so C opens nothing yet either.
        assertRejected(attempt(A))
        assertRejected(attempt(C))

        // The new vault takes C as its Duress Password. Gallery and Show Secret refuse it and promote nothing.
        val again = unlocked(B)
        val secondDecoy = setDuress(again, C)
        val keyFileSecond = layout.vaultKey.readBytes()
        assertRejected(verify(C))
        // The password step of Show Secret and of the other flows, with the vault open as it is there: refused, and the session stays.
        val holder = VaultHolder(vault)
        assertTrue(holder.adopt(again, holder.unlockEpoch()))
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        assertEquals(PasswordCheck.Rejected, runBlocking { checkAppPassword(holder, C.toCharArray()) })
        assertSame(again, holder.openSession())
        assertEquals(firstDecoy, again.listWallets())
        holder.lock()
        assertArrayEquals(keyFileSecond, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        val stillB = unlocked(B)
        assertEquals(firstDecoy, stillB.listWallets())
        stillB.close()

        // Unlocking with C promotes its decoy, and B opens nothing from then on.
        val second = unlocked(C)
        assertEquals(0, second.side)
        assertEquals(secondDecoy, second.listWallets())
        awaitCleanup()
        assertFalse(layout.promotionMarker.exists())
        assertFalse(layout.promotionRemnant.exists())
        second.close()
        assertRejected(attempt(B))
        unlocked(C).close()
    }

    private fun attempt(password: String): UnlockResult {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return runBlocking { vault.unlock(password.toCharArray()) }
    }

    private fun verify(password: String): UnlockResult {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return runBlocking { vault.verify(password.toCharArray()) }
    }

    private fun unlocked(password: String): VaultSession {
        val result = attempt(password)
        assertTrue("Expected Unlocked but was $result", result is UnlockResult.Unlocked)
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertTrue("Expected Rejected but was $result", result is UnlockResult.Rejected)
    }

    /** Sets the Duress Password with one decoy wallet, and returns the decoy's wallet ids. */
    private fun setDuress(session: VaultSession, password: String): List<String> {
        var ids = emptyList<String>()
        val result = runBlocking {
            vault.setDuress(session, password.toCharArray()) { decoy ->
                decoy.createWallet()
                ids = decoy.listWallets()
            }
        }
        assertEquals(DuressResult.Enabled, result)
        return ids
    }

    /** Waits until the cleanup that a promotion left has removed its marker, which it does last. */
    private fun awaitCleanup() {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (layout.promotionMarker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("the cleanup of the promotion finished", layout.promotionMarker.exists())
    }

    private companion object {
        const val A = "Tq7#vLm2@Rx9!Kp4"
        const val B = "Wn3\$hZc8&Jd5^Fs1"
        const val C = "Pb6*yGt4%Mq2+Ve7"
    }
}
