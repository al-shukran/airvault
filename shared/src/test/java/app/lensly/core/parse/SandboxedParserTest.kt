package app.lensly.core.parse

import app.lensly.core.registry.CallDecode
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.KeystoneVectors
import app.lensly.core.ur.framesOf
import app.lensly.fuzz.FuzzSeeds
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

private const val ONE_FRAME = 64 * 1024
private const val MULTI_PART_FRAGMENT = 60
private const val MULTI_PART_FRAMES = 40
private const val TIMEOUT_MILLIS = 50L

/** Bounds a silent sandbox in a test, so a missing timeout fails the test instead of hanging it. */
private const val SILENCE_BOUND_SECONDS = 10L
private val TEXT = listOf("https://example.com")

class SandboxedParserTest {

    @Test
    fun aSandboxThatDiesRefusesEveryCallAndIsReset() {
        val sandbox = ScriptedSandbox { _, _ -> throw IllegalStateException("The Parser Sandbox died.") }
        val parser = SandboxedParser(sandbox)

        assertEquals(ScanReply.Refused(CANNOT_BE_READ), parser.scan(TEXT))
        assertEquals(CallDecode.Refused(CANNOT_BE_READ), parser.decodeCall(ByteArray(4), emptyList()))
        assertEquals(2, sandbox.resets)
    }

    @Test
    fun aSandboxThatNeverAnswersIsRefusedAtItsTimeout() {
        val release = CountDownLatch(1)
        val sandbox = ScriptedSandbox { _, _ ->
            release.await(SILENCE_BOUND_SECONDS, TimeUnit.SECONDS)
            ParserWire.encodeScanReply(ScanReply.NotPart)
        }
        val parser = SandboxedParser(sandbox, timeoutMillis = TIMEOUT_MILLIS)
        try {
            assertEquals(ScanReply.Refused(CANNOT_BE_READ), parser.scan(TEXT))
        } finally {
            release.countDown()
        }
        assertEquals(1, sandbox.resets)
    }

    @Test
    fun aSandboxThatNeverAnswersDoesNotGrowTheCallsInFlight() {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resets = AtomicInteger()
        val sandbox = object : SandboxTransport {
            override fun call(method: Int, payload: ByteArray): ByteArray {
                calls.incrementAndGet()
                blockLikeABinderCall(release)
                return ParserWire.encodeScanReply(ScanReply.NotPart)
            }

            override fun reset() {
                resets.incrementAndGet()
            }
        }
        val parser = SandboxedParser(sandbox, timeoutMillis = TIMEOUT_MILLIS)
        try {
            repeat(MAX_CALLS_IN_FLIGHT + 3) {
                assertEquals(CallDecode.Refused(CANNOT_BE_READ), parser.decodeCall(ByteArray(4), emptyList()))
            }
        } finally {
            release.countDown()
        }
        // The calls past the bound are refused without reaching the sandbox, and each refusal drops the connection.
        assertEquals(MAX_CALLS_IN_FLIGHT, calls.get())
        assertEquals(MAX_CALLS_IN_FLIGHT + 3, resets.get())
    }

    @Test
    fun aMalformedReplyIsRefused() {
        val valid = ParserWire.encodeScanReply(ScanReply.NotPart)
        val replies = listOf(
            ByteArray(0),
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            ByteBuffer.allocate(8).putInt(2).putInt(1).array(),
            ByteBuffer.allocate(8).putInt(1).putInt(99).array(),
            ParserWire.encodeScanReply(ScanReply.Incomplete(1.5)),
            ByteArray(ParserWire.MAX_REPLY_BYTES + 1),
        )
        for (reply in replies) {
            val parser = SandboxedParser(ScriptedSandbox { _, _ -> reply })
            assertEquals(ScanReply.Refused(CANNOT_BE_READ), parser.scan(TEXT), "a reply of ${reply.size} bytes")
        }
        val staticReply = SandboxedParser(ScriptedSandbox { _, _ -> byteArrayOf(0, 0, 0, 1) })
        assertEquals(CallDecode.Refused(CANNOT_BE_READ), staticReply.decodeCall(ByteArray(4), emptyList()))
    }

    @Test
    fun aPartThatCannotBeSentIsRefusedWithoutACall() {
        val sandbox = ScriptedSandbox { _, _ -> error("The call must not be made.") }

        assertEquals(ScanReply.Refused(CANNOT_BE_READ), SandboxedParser(sandbox).scan(listOf("ur:" + "a".repeat(9_000))))
        assertEquals(0, sandbox.calls)
    }

    @Test
    fun aParserThatFailsAnswersWithARefusal() {
        val failing = object : RequestParser {
            override fun scan(parts: List<String>): ScanReply = InProcessParser.scan(parts)

            override fun decodeCall(calldata: ByteArray, params: List<WhitelistParam>): CallDecode =
                throw IllegalStateException("test")
        }

        val reply = SandboxAnswers(failing).answer(ParserWire.DECODE_CALL, ParserWire.encodeCallRequest(ByteArray(4), emptyList()))
        assertEquals(CallDecode.Refused(CANNOT_BE_READ), ParserWire.decodeCallReply(reply))
    }

    @Test
    fun aMethodThatDoesNotExistOrAPayloadThatCannotBeReadIsRefused() {
        val unknownMethod = SandboxAnswers().answer(99, ByteArray(0))
        val garbage = SandboxAnswers().answer(ParserWire.SCAN, byteArrayOf(1, 2, 3))

        assertEquals(ScanReply.Refused(CANNOT_BE_READ), ParserWire.decodeScanReply(unknownMethod))
        assertEquals(ScanReply.Refused(CANNOT_BE_READ), ParserWire.decodeScanReply(garbage))
    }

    @Test
    fun aScanCallThatDoesNotGoOnFromTheScanHeldIsRefusedAndReadsNothing() {
        val (first, second) = multiPartFrames().let { it[0] to it[1] }
        val answers = SandboxAnswers()
        fun call(session: Long, base: Int, part: String): ScanReply =
            ParserWire.decodeScanReply(answers.answer(ParserWire.SCAN, ParserWire.encodeScanRequest(session, base, listOf(part))))

        // Session 1 takes its first part. Session 2 then starts a scan of its own, so session 1 is no longer held.
        assertIs<ScanReply.Incomplete>(call(1L, 0, first))
        assertIs<ScanReply.Incomplete>(call(2L, 0, first))
        // The base is the number of parts that session 1 holds, but session 2 is the one held, so the part is not read into it.
        assertEquals(ScanReply.Refused(SCAN_NOT_HELD), call(1L, 1, second))
        // Session 2 is held, but the base is not the number of its parts.
        assertEquals(ScanReply.Refused(SCAN_NOT_HELD), call(2L, 5, second))
    }

    @Test
    fun aScanSendsOnlyTheNewPartsOfEachCall() {
        val frames = multiPartFrames()
        val calls = mutableListOf<ParserWire.ScanRequest>()
        val parser = SandboxedParser(RecordingSandbox(SandboxAnswers(), calls))

        val parts = mutableListOf<String>()
        val replies = frames.map { frame ->
            parts += frame
            parser.scan(parts.toList())
        }

        assertEquals(frames.indices.toList(), calls.map { it.base })
        assertEquals(1, calls.first().parts.size)
        assertTrue(calls.drop(1).all { it.parts.size == 1 })
        assertEquals(1, calls.map { it.session }.distinct().size)
        assertContentEquals(ParserWire.encodeScanReply(InProcessParser.scan(frames)), ParserWire.encodeScanReply(replies.last()))
    }

    @Test
    fun aSandboxedScanRepliesAsADirectScanAtEveryStep() {
        val frames = multiPartFrames()
        val parser = SandboxedParser(RecordingSandbox(SandboxAnswers(), mutableListOf()))

        val parts = mutableListOf<String>()
        for (frame in frames) {
            // A code that is not part of the request comes between two frames. It is not counted, so the next frame still follows on.
            assertEquals(ScanReply.NotPart, parser.scan(parts + "https://example.com"))
            parts += frame
            assertContentEquals(
                ParserWire.encodeScanReply(InProcessParser.scan(parts)),
                ParserWire.encodeScanReply(parser.scan(parts.toList())),
            )
        }
    }

    @Test
    fun aSandboxThatLostTheScanIsSentTheWholeListAgain() {
        val frames = multiPartFrames()
        val restartAt = frames.size / 2
        val calls = mutableListOf<ParserWire.ScanRequest>()
        var answers = SandboxAnswers()
        val sandbox = object : SandboxTransport {
            override fun call(method: Int, payload: ByteArray): ByteArray {
                if (method == ParserWire.SCAN) calls += ParserWire.decodeScanRequest(payload)
                return answers.answer(method, payload)
            }

            override fun reset() = Unit
        }
        val parser = SandboxedParser(sandbox)

        val parts = mutableListOf<String>()
        val replies = frames.mapIndexed { index, frame ->
            // The sandbox restarts between two calls, so it no longer holds the scan.
            if (index == restartAt) answers = SandboxAnswers()
            parts += frame
            parser.scan(parts.toList())
        }

        assertContentEquals(ParserWire.encodeScanReply(InProcessParser.scan(frames)), ParserWire.encodeScanReply(replies.last()))
        // The call after the restart is refused, and the app then sends the whole list as a new scan.
        val resent = calls[restartAt + 1]
        assertEquals(0, resent.base)
        assertEquals(restartAt + 1, resent.parts.size)
    }

    @Test
    fun scansOfTwoParsersOnOneSandboxDoNotMixUp() {
        val first = multiPartFrames()
        val second = multiPartFrames("eth-sign-request-no-origin.txt")
        val answers = SandboxAnswers()
        val firstParser = SandboxedParser(RecordingSandbox(answers, mutableListOf()))
        val secondParser = SandboxedParser(RecordingSandbox(answers, mutableListOf()))

        // The two scans take a part each in turn, so they hold the same number of parts at every step.
        val firstParts = mutableListOf<String>()
        val secondParts = mutableListOf<String>()
        var firstReply: ScanReply? = null
        var secondReply: ScanReply? = null
        for (step in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(step)?.let {
                firstParts += it
                firstReply = firstParser.scan(firstParts.toList())
            }
            second.getOrNull(step)?.let {
                secondParts += it
                secondReply = secondParser.scan(secondParts.toList())
            }
        }

        assertContentEquals(ParserWire.encodeScanReply(InProcessParser.scan(first)), ParserWire.encodeScanReply(assertNotNull(firstReply)))
        val secondDirect = ParserWire.encodeScanReply(InProcessParser.scan(second))
        assertContentEquals(secondDirect, ParserWire.encodeScanReply(assertNotNull(secondReply)))
    }

    @Test
    fun aSandboxedScanIsTheDirectScanForEveryFixture() {
        val fixtures = listOf(
            listOf(KeystoneVectors.ur("eth-sign-request-origin.txt")),
            listOf(KeystoneVectors.ur("eth-sign-request-no-origin.txt")),
        ) + FuzzSeeds.bytes("eth-sign-request").filter { it.isNotEmpty() }.map { cbor ->
            // An empty payload has no UR form, since the encoder cannot frame zero bytes. A scanner never sees one.
            listOf(framesOf(UR(EthSignRequest.UR_TYPE, cbor), ONE_FRAME).first())
        }

        for (parts in fixtures) {
            val direct = InProcessParser.scan(parts)
            val sandboxed = inProcessSandboxParser.scan(parts)
            assertContentEquals(
                ParserWire.encodeScanReply(direct),
                ParserWire.encodeScanReply(sandboxed),
                parts.first().take(40),
            )
        }
    }

    @Test
    fun anOriginLongerThanAShortTextCrossesTheWire() {
        val origin = "https://example.com/" + "a".repeat(5_000)
        val request = EthSignRequest(null, "hello".toByteArray(), DataType.PERSONAL_MESSAGE, null, emptyList(), null, null, origin)

        val reply = ParserWire.encodeScanReply(ScanReply.Complete(InProcessParser.parsedOf(request)))

        assertEquals(origin, assertIs<ScanReply.Complete>(ParserWire.decodeScanReply(reply)).parsed.request.origin)
    }

    @Test
    fun aStrayUrCodeOverTheLimitsIsNotPartOfTheRequestAndTheRequestStillCompletes() {
        val stray = "ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS + 1}/aa"
        val frames = multiPartFrames()

        assertEquals(ScanReply.NotPart, InProcessParser.scan(listOf(stray)))
        assertEquals(ScanReply.NotPart, inProcessSandboxParser.scan(listOf(frames.first(), stray)))
        val direct = InProcessParser.scan(frames)
        assertIs<ScanReply.Complete>(direct)
        assertContentEquals(
            ParserWire.encodeScanReply(direct),
            ParserWire.encodeScanReply(InProcessParser.scan(listOf(frames.first(), stray) + frames.drop(1))),
        )
    }

    /**
     * Blocks until [release] opens or [SILENCE_BOUND_SECONDS] pass. A binder call cannot be interrupted, so an interrupt does not end the
     * wait, and a call that its caller has given up on keeps its thread, as a real one does.
     */
    private fun blockLikeABinderCall(release: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SILENCE_BOUND_SECONDS)
        while (release.count > 0 && System.nanoTime() < deadline) {
            try {
                release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                // Not an end: the binder call goes on.
            }
        }
    }

    /**
     * The frames of the request in the [vector] too large for one code, up to the frame that completes it, as a scan reads them. The
     * stream is endless, so it is cut at [MULTI_PART_FRAMES].
     */
    private fun multiPartFrames(vector: String = "eth-sign-request-origin.txt"): List<String> {
        val frames = framesOf(URDecoder.decode(KeystoneVectors.ur(vector)), MULTI_PART_FRAGMENT)
            .take(MULTI_PART_FRAMES)
            .toList()
        val complete = frames.indices.first { InProcessParser.scan(frames.take(it + 1)) is ScanReply.Complete }
        return frames.take(complete + 1)
    }
}

/** A sandbox that answers as [answers] does, and adds each scan call it gets to [scanCalls]. */
private class RecordingSandbox(
    private val answers: SandboxAnswers,
    private val scanCalls: MutableList<ParserWire.ScanRequest>,
) : SandboxTransport {
    override fun call(method: Int, payload: ByteArray): ByteArray {
        if (method == ParserWire.SCAN) scanCalls += ParserWire.decodeScanRequest(payload)
        return answers.answer(method, payload)
    }

    override fun reset() = Unit
}
