package app.lensly.core.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * After a Duress Password promotion on the device, with SELinux enforcing, the old `unlock.bin` is overwritten and no file in the
 * app's data holds its bytes. The system refuses an app a hard link in its own folder, so the overwrite goes through a descriptor.
 */
@RunWith(AndroidJUnit4::class)
class DuressPromotionOverwriteTest {
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
    fun theOldUnlockFileIsOverwrittenAndItsBytesAreNowhereInTheDataFolder() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(A.toCharArray()) })
        val real = unlocked(A)
        val setup = runBlocking { vault.setDuress(real, B.toCharArray()) { it.createWallet() } }
        assertEquals(DuressResult.Enabled, setup)
        real.close()
        val oldBytes = layout.vaultKey.readBytes()
        // A descriptor on the old file, as any process that held it open would have: the rename only unlinks the file, and the descriptor still reaches its bytes.
        RandomAccessFile(layout.vaultKey, "r").use { held ->
            val promoted = unlocked(B)
            awaitCleanup()
            promoted.close()

            assertFalse("the key file in place is the decoy's", layout.vaultKey.readBytes().contentEquals(oldBytes))
            assertEquals(oldBytes.size.toLong(), held.length())
            val left = ByteArray(oldBytes.size)
            held.seek(0)
            held.readFully(left)
            assertFalse("the old unlock.bin was overwritten, not only unlinked", left.contentEquals(oldBytes))
        }
        val holders = filesBelow(context.filesDir.parentFile!!).filter { it.isFile && it.readBytes().containsSlice(oldBytes) }
        assertTrue("files that still hold the old unlock.bin: $holders", holders.isEmpty())
    }

    private fun unlocked(password: String): VaultSession {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue("Expected Unlocked but was $result", result is UnlockResult.Unlocked)
        return (result as UnlockResult.Unlocked).session
    }

    private fun awaitCleanup() {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (layout.promotionMarker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse("the cleanup of the promotion finished", layout.promotionMarker.exists())
    }

    private fun filesBelow(dir: File): List<File> = dir.listFiles().orEmpty().flatMap { if (it.isDirectory) filesBelow(it) else listOf(it) }

    private fun ByteArray.containsSlice(part: ByteArray): Boolean =
        size >= part.size && (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private companion object {
        const val A = "Tq7#vLm2@Rx9!Kp4"
        const val B = "Wn3\$hZc8&Jd5^Fs1"
    }
}
