package app.lensly.ui.qr

import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** [AnimatedQr] encodes off the main thread, and a revisit of a payload gets the frames it encoded before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnimatedQrFramesTest {

    @Test
    fun theEncodeRunsOffTheMainThread() = runBlocking {
        val main = Looper.getMainLooper().thread
        assertSame(main, Thread.currentThread(), "the test itself must run on the main thread")

        val encodedOn = mutableListOf<Thread>()
        encodeQrFrames(listOf("ur:one", "ur:two"), keepCase = false) { _, _ ->
            encodedOn += Thread.currentThread()
            QrPixels(size = 1, argb = intArrayOf(0xFF000000.toInt()))
        }

        assertEquals(2, encodedOn.size)
        encodedOn.forEach { assertNotSame(main, it) }
    }

    @Test
    fun aRevisitOfTheSamePayloadGetsTheSameBitmaps() = runBlocking {
        val parts = listOf("ur:cache-test/${System.nanoTime()}")
        val frames = encodeQrFrames(parts, keepCase = false)
        QrFrameCache.put(parts, keepCase = false, encoded = frames)

        // A fresh list with the same payload is the same key, as a recomposition with the same code passes.
        val revisit = QrFrameCache.get(parts.toList(), keepCase = false)

        assertSame(frames.single(), revisit?.single())
        assertNull(QrFrameCache.get(parts, keepCase = true), "the same payload encoded differently is another entry")
    }
}
