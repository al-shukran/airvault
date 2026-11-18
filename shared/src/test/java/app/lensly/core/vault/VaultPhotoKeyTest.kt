package app.lensly.core.vault

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A vault's photo folder keeps sealed media only. Plain photos from an earlier build are shredded when the vault is unlocked. */
class VaultPhotoKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        assertTrue(runBlocking { vault.create(APP.toCharArray()) } == CreateResult.Created)
    }

    @Test
    fun anUnlockShredsPlainPhotosAndKeepsSealedOnes() {
        val folder = layout.photoDir(decoy = false).apply { mkdirs() }
        val plainPhoto = File(folder, "IMG_20260101_000000_000.jpg").apply { writeBytes(ByteArray(8) { 1 }) }
        val plainVideo = File(folder, "VID_20260101_000000_000.mp4").apply { writeBytes(ByteArray(8) { 2 }) }
        val sealed = File(folder, "IMG_20260101_000001_000.sealed").apply { writeBytes(ByteArray(64) { 3 }) }

        val result = runBlocking { vault.unlock(APP.toCharArray()) }

        (result as UnlockResult.Unlocked).session.close()
        assertFalse(plainPhoto.exists())
        assertFalse(plainVideo.exists())
        assertTrue(sealed.exists())
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
