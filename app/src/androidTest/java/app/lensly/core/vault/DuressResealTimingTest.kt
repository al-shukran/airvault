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
 * 3 MB and 3 videos of 40 MB (about one minute at HD). The time is logged under the tag `DuressResealTiming`; the test only checks that
 * the set succeeds and that every photo has its twin afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DuressResealTimingTest {
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
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "setDuress with $PHOTOS photos of $PHOTO_MB MB and $VIDEOS videos of $VIDEO_MB MB: $ms ms")
        assertEquals(DuressResult.Enabled, result)
        session.close()
        assertEquals(PHOTOS + VIDEOS, layout.photoDir(1).list().orEmpty().size)
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
    }
}
