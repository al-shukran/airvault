package app.lensly.core.qr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.junit.Test

private const val TIMEOUT_MILLIS = 50L

/** Bounds a blocked fake in a test, so a client that never times out fails the test instead of hanging it. */
private const val BLOCKED_BOUND_SECONDS = 10L

class QrDecodeClientTest {
    private val luma = ByteArray(4)

    @Test
    fun aReplyCarriesItsTexts() {
        val transport = ScriptedTransport { _, _, _ -> listOf("https://example.com") }

        assertEquals(
            QrDecodeOutcome.Answered(listOf("https://example.com")),
            QrDecodeClient(transport).decode(luma, width = 2, height = 2),
        )
        assertEquals(0, transport.disconnects)
    }

    @Test
    fun aFrameWithNoCodeIsAnsweredWithNoTexts() {
        val transport = ScriptedTransport { _, _, _ -> emptyList() }

        assertEquals(QrDecodeOutcome.Answered(emptyList()), QrDecodeClient(transport).decode(luma, width = 2, height = 2))
    }

    @Test
    fun theFrameReachesTheTransportAsItWasSent() {
        val transport = ScriptedTransport { _, _, _ -> emptyList() }
        val frame = byteArrayOf(1, 2, 3, 4)

        QrDecodeClient(transport).decode(frame, width = 2, height = 2)

        assertEquals(frame.toList(), transport.frame?.toList())
        assertEquals(2 to 2, transport.size)
    }

    @Test
    fun aSandboxThatFailsIsUnavailableAndTheConnectionIsDropped() {
        val transport = ScriptedTransport { _, _, _ -> throw IllegalStateException("The QR decode sandbox died.") }

        assertEquals(QrDecodeOutcome.Unavailable, QrDecodeClient(transport).decode(luma, width = 2, height = 2))
        assertEquals(1, transport.disconnects)
    }

    @Test
    fun aSandboxThatNeverAnswersIsUnavailableAtTheTimeout() {
        val release = CountDownLatch(1)
        val transport = ScriptedTransport { _, _, _ ->
            release.await(BLOCKED_BOUND_SECONDS, TimeUnit.SECONDS)
            emptyList()
        }
        try {
            assertEquals(
                QrDecodeOutcome.Unavailable,
                QrDecodeClient(transport, TIMEOUT_MILLIS).decode(luma, width = 2, height = 2),
            )
            assertEquals(1, transport.disconnects)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aReplyIsCheckedAgainstTheLimitsBeforeItIsReturned() {
        val tooLong = "x".repeat(QrDecodeLimits.MAX_TEXT_CHARS + 1)
        val transport = ScriptedTransport { _, _, _ -> listOf("", tooLong, "a", "b", "c", "d", "e") }

        assertEquals(
            QrDecodeOutcome.Answered(listOf("a", "b", "c", "d")),
            QrDecodeClient(transport).decode(luma, width = 2, height = 2),
        )
    }

    @Test
    fun closingTheClientDropsTheConnection() {
        val transport = ScriptedTransport { _, _, _ -> emptyList() }

        QrDecodeClient(transport).close()

        assertEquals(1, transport.disconnects)
    }

    private class ScriptedTransport(private val answer: (ByteArray, Int, Int) -> List<String>) : QrDecodeTransport {
        var frame: ByteArray? = null
        var size: Pair<Int, Int>? = null
        var disconnects = 0

        override fun decode(luma: ByteArray, width: Int, height: Int): List<String> {
            frame = luma
            size = width to height
            return answer(luma, width, height)
        }

        override fun disconnect() {
            disconnects++
        }
    }
}
