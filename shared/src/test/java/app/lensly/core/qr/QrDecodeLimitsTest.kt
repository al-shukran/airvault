package app.lensly.core.qr

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class QrDecodeLimitsTest {

    @Test
    fun aCameraFrameIsAccepted() {
        assertNull(QrDecodeLimits.frameProblem(width = 1280, height = 720, rowStride = 1280, sharedBytes = 1280L * 720))
    }

    @Test
    fun aFrameWithNoPixelsIsRefused() {
        assertNotNull(QrDecodeLimits.frameProblem(width = 0, height = 720, rowStride = 0, sharedBytes = 0L))
        assertNotNull(QrDecodeLimits.frameProblem(width = 1280, height = 0, rowStride = 1280, sharedBytes = 0L))
    }

    @Test
    fun theSideLimitIsInclusive() {
        assertNull(QrDecodeLimits.frameProblem(width = 4096, height = 1, rowStride = 4096, sharedBytes = 4096L))
        assertNotNull(QrDecodeLimits.frameProblem(width = 4097, height = 1, rowStride = 4097, sharedBytes = 4097L))
        assertNotNull(QrDecodeLimits.frameProblem(width = 1, height = 4097, rowStride = 1, sharedBytes = 4097L))
    }

    @Test
    fun thePixelLimitIsInclusive() {
        // 3840 x 2160 is exactly the pixel limit. One more row is over it, while each side is still under the side limit.
        assertNull(QrDecodeLimits.frameProblem(width = 3840, height = 2160, rowStride = 3840, sharedBytes = 3840L * 2160))
        assertNotNull(QrDecodeLimits.frameProblem(width = 3840, height = 2161, rowStride = 3840, sharedBytes = 3840L * 2161))
    }

    @Test
    fun aRowStrideShorterThanTheWidthIsRefused() {
        assertNotNull(QrDecodeLimits.frameProblem(width = 640, height = 480, rowStride = 639, sharedBytes = 639L * 480))
    }

    @Test
    fun theByteLimitCatchesPaddedRowsThatThePixelLimitAllows() {
        // One pixel wide, but each row is 8192 bytes: 4096 rows make 32 MB, over the 20 MB input cap.
        assertNotNull(QrDecodeLimits.frameProblem(width = 1, height = 4096, rowStride = 8192, sharedBytes = 8192L * 4096))
        // Exactly 20 MB is accepted.
        assertNull(QrDecodeLimits.frameProblem(width = 1, height = 2560, rowStride = 8192, sharedBytes = 8192L * 2560))
    }

    @Test
    fun aFrameLargerThanItsSharedMemoryIsRefused() {
        assertNotNull(QrDecodeLimits.frameProblem(width = 1280, height = 720, rowStride = 1280, sharedBytes = 1280L * 720 - 1))
    }

    @Test
    fun aReplyKeepsTheFirstFourNonEmptyTextsThatFitTheLengthLimit() {
        val tooLong = "x".repeat(QrDecodeLimits.MAX_TEXT_CHARS + 1)
        val longest = "y".repeat(QrDecodeLimits.MAX_TEXT_CHARS)

        assertEquals(
            listOf("a", longest, "b", "c"),
            QrDecodeLimits.acceptReply(listOf("", "a", tooLong, longest, "b", "c", "d")),
        )
    }
}
