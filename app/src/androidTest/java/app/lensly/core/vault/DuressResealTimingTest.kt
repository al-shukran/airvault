package app.lensly.core.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lensly.ui.camera.PhotoStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmark: how long setting a Duress Password takes with a large cover media set on the device's real files and Keystore: 50 photos of
 * 3 MB and 3 videos of 40 MB (about one minute at HD), with the production key derivation. Both times are logged under the tag
 * `DuressResealTiming`: when the set returns, and when the last copy is made. The test checks that the set returns within [SETUP_LIMIT_MS]
 * and that, once the Duress Password promotes the decoy, every photo and video reads back as saved.
 */
@RunWith(AndroidJUnit4::class)
class DuressResealTimingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = FakeClock()
    private val vault = instrumentedVault(context, clock, null)
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
    fun settingTheDuressPasswordWithFiftyPhotosAndThreeVideos() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val session = (runBlocking { vault.unlock(APP.toCharArray()) } as UnlockResult.Unlocked).session
        session.createWallet()
        val cover = PhotoStore.forCover(layout)
        val photo = ByteArray(PHOTO_MB * MB) { (it * 7).toByte() }
        repeat(PHOTOS) { assertTrue(cover.savePhoto(photo.copyOf(), nowMs = 1_000L + it)) }
        val video = ByteArray(VIDEO_MB * MB) { (it * 13).toByte() }
        repeat(VIDEOS) {
            val recording = File(context.cacheDir, "recording$it.mp4").also { f -> f.writeBytes(video) }
            assertTrue(cover.saveVideo(recording, nowMs = 5_000L + it))
        }

        val started = System.nanoTime()
        val result = runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } }
        val setupMs = (System.nanoTime() - started) / 1_000_000
        runBlocking { vault.awaitPhotoCopies() }
        val copiesMs = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "setDuress with $PHOTOS photos of $PHOTO_MB MB and $VIDEOS videos of $VIDEO_MB MB: returned after $setupMs ms, copies done after $copiesMs ms")
        assertEquals(DuressResult.Enabled, result)
        assertTrue("setup must return well before the copies end", setupMs < SETUP_LIMIT_MS)
        session.close()
        assertEquals(PHOTOS + VIDEOS, layout.photoDir(1).list().orEmpty().size)

        // A twin that a failed copy left as random bytes has the same name and size, so the decoy's gallery must open every file.
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val promoted = (runBlocking { vault.unlock(DURESS.toCharArray()) } as UnlockResult.Unlocked).session
        runBlocking { vault.awaitCleanup() }
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = promoted.photoPrivateKey())
        val saved = gallery.list()
        assertEquals(PHOTOS + VIDEOS, saved.size)
        assertEquals(PHOTOS, saved.count { !it.isVideo })
        saved.forEach { media ->
            if (media.isVideo) {
                gallery.videoSource(media.file)!!.use { source -> assertEquals(video.size.toLong(), source.size) }
            } else {
                assertTrue("a photo must read back as saved", photo.contentEquals(gallery.photoBytes(media.file)))
            }
        }
        gallery.close()
        promoted.close()
    }

    private companion object {
        const val TAG = "DuressResealTiming"
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        const val MB = 1024 * 1024
        const val PHOTOS = 50
        const val PHOTO_MB = 3
        const val VIDEOS = 3
        const val VIDEO_MB = 40
        const val SETUP_LIMIT_MS = 5_000L
    }
}
