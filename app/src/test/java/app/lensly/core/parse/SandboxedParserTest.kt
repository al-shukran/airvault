package app.lensly.core.parse

import app.lensly.core.registry.StaticDecode
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.KeystoneVectors
import app.lensly.core.ur.framesOf
import app.lensly.fuzz.FuzzSeeds
import com.sparrowwallet.hummingbird.UR
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

private const val ONE_FRAME = 64 * 1024
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
        assertEquals(StaticDecode.Refused(CANNOT_BE_READ), parser.decodeStatic(ByteArray(4), emptyList()))
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
        assertEquals(StaticDecode.Refused(CANNOT_BE_READ), staticReply.decodeStatic(ByteArray(4), emptyList()))
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
            override fun scan(parts: List<String>): ScanReply = throw OutOfMemoryError("test")

            override fun decodeStatic(calldata: ByteArray, params: List<WhitelistParam>): StaticDecode =
                throw IllegalStateException("test")
        }

        val reply = answer(failing, ParserWire.SCAN, ParserWire.encodeScanRequest(TEXT))
        assertEquals(ScanReply.Refused(CANNOT_BE_READ), ParserWire.decodeScanReply(reply))
    }

    @Test
    fun aMethodThatDoesNotExistOrAPayloadThatCannotBeReadIsRefused() {
        val unknownMethod = answer(InProcessParser, 99, ByteArray(0))
        val garbage = answer(InProcessParser, ParserWire.SCAN, byteArrayOf(1, 2, 3))

        assertEquals(ScanReply.Refused(CANNOT_BE_READ), ParserWire.decodeScanReply(unknownMethod))
        assertEquals(ScanReply.Refused(CANNOT_BE_READ), ParserWire.decodeScanReply(garbage))
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
}
