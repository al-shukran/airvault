package app.lensly.app.export

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device: the Brotli encoder is the native library, which a JVM test cannot load. The demo pairing must encode to the golden
 * frames that Lumio reads back, and its code must stay within the size the pairing is designed for.
 */
@RunWith(AndroidJUnit4::class)
class DemoPairingBrotliTest {

    @Test
    fun theDemoPairingEncodesToTheGoldenFramesWithinTheSizeBudget() {
        val payload = DemoPairingGolden.payload()
        val code = PairingCodec.encode(payload, BrotliEncoder)
        val frames = PairingCodec.urParts(payload, BrotliEncoder)
        // The frames are logged so the golden can be re-recorded from logcat when the pairing changes.
        Log.i(RECORD_TAG, "code=${code.size} bytes, frames=${frames.size}")
        frames.forEach { Log.i(RECORD_TAG, it) }

        assertTrue("the code is ${code.size} bytes", code.size <= SIZE_BUDGET_BYTES)
        assertEquals(DemoPairingGolden.FRAMES_BROTLI, frames)
    }

    private companion object {
        const val RECORD_TAG = "DemoPairingRecord"
        const val SIZE_BUDGET_BYTES = 3600
    }
}
