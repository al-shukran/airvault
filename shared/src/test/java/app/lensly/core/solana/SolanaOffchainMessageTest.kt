package app.lensly.core.solana

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/** The off-chain message parser: the layout, the two formats Lensly signs, and the bytes it refuses. */
class SolanaOffchainMessageTest {

    @Test
    fun aRestrictedAsciiMessageParsesWithItsDomainSignersAndText() {
        val envelope = offchainEnvelope("hello".toByteArray(), domain = key(5), signers = listOf(key(1), key(2)))
        val message = parsed(envelope)
        assertEquals(SolanaOffchainMessage.Format.RESTRICTED_ASCII, message.format)
        assertContentEquals(key(5), message.applicationDomain)
        assertEquals(listOf(key(1).toList(), key(2).toList()), message.signers.map { it.toList() })
        assertEquals("hello", message.text)
    }

    /** A limited UTF-8 message keeps its line breaks, and a trailing one, exactly as the signer wrote them. */
    @Test
    fun aLimitedUtf8MessageKeepsItsLineBreaks() {
        val text = "Sign in to Example\nNonce 42\n"
        val message = parsed(offchainEnvelope(text.toByteArray(Charsets.UTF_8), format = 1))
        assertEquals(SolanaOffchainMessage.Format.LIMITED_UTF8, message.format)
        assertEquals(text, message.text)
    }

    /** Bytes without the signing domain, or with a damaged one, are not off-chain messages, whatever their length. */
    @Test
    fun bytesWithoutTheSigningDomainAreRejected() {
        val envelope = offchainEnvelope("hello".toByteArray())
        val zeroFirstByte = envelope.copyOf().also { it[0] = 0 }
        val damagedText = envelope.copyOf().also { it[1] = 'X'.code.toByte() }
        val inputs = listOf(ByteArray(0), byteArrayOf(1, 2, 3), "hello".toByteArray(), zeroFirstByte, damagedText)
        for (bytes in inputs) {
            assertEquals(SolanaOffchainMessage.NOT_OFF_CHAIN, rejected(bytes), "input ${bytes.toList()}")
        }
    }

    @Test
    fun aHeaderVersionOtherThanZeroIsRejected() {
        assertEquals(SolanaOffchainMessage.UNSUPPORTED_VERSION, rejected(offchainEnvelope("hello".toByteArray(), version = 1)))
    }

    /** Only restricted ASCII and limited UTF-8 are signed. Extended UTF-8, and any other format byte, is refused. */
    @Test
    fun onlyRestrictedAsciiAndLimitedUtf8AreSigned() {
        for (format in listOf(2, 3, 0x7f, 0xff)) {
            val envelope = offchainEnvelope("hello".toByteArray(), format = format)
            assertEquals(SolanaOffchainMessage.UNSUPPORTED_FORMAT, rejected(envelope), "format $format")
        }
    }

    @Test
    fun aMessageNamingNoSignerIsRejected() {
        assertEquals(SolanaOffchainMessage.NO_SIGNER, rejected(offchainEnvelope("hello".toByteArray(), signers = emptyList())))
    }

    /** Every strict prefix of a valid message is rejected, and no read runs past the end. */
    @Test
    fun everyTruncationOfAValidMessageIsRejectedWithoutAnError() {
        val envelope = offchainEnvelope("hello".toByteArray(), signers = listOf(key(1), key(2)))
        for (length in 0 until envelope.size) {
            assertIs<SolanaOffchainMessage.Result.Rejected>(SolanaOffchainMessage.parse(envelope.copyOf(length)), "length $length")
        }
        assertEquals("hello", parsed(envelope).text)
    }

    /** A length field that disagrees with the bytes after it is rejected, short or long. A zero length is an empty message. */
    @Test
    fun aLengthThatDisagreesWithTheMessageIsRejected() {
        val text = "hello".toByteArray()
        assertEquals(SolanaOffchainMessage.LENGTH_MISMATCH, rejected(offchainEnvelope(text, declaredLength = 6)))
        assertEquals(SolanaOffchainMessage.LENGTH_MISMATCH, rejected(offchainEnvelope(text, declaredLength = 4)))
        assertEquals(SolanaOffchainMessage.LENGTH_MISMATCH, rejected(offchainEnvelope(text, declaredLength = 0xffff)))
        assertEquals(SolanaOffchainMessage.EMPTY_MESSAGE, rejected(offchainEnvelope(text, declaredLength = 0)))
    }

    /** The envelope is at most a Solana packet, 1232 bytes. With one signer, 1147 bytes of text fit and 1148 do not. */
    @Test
    fun anEnvelopeLargerThanAPacketIsRejected() {
        val fits = offchainEnvelope("a".repeat(1147).toByteArray(), signers = listOf(key(1)))
        assertEquals(1232, fits.size)
        assertEquals(1147, parsed(fits).text.length)
        val tooLong = offchainEnvelope("a".repeat(1148).toByteArray(), signers = listOf(key(1)))
        assertEquals(SolanaOffchainMessage.TOO_LONG, rejected(tooLong))
    }

    @Test
    fun restrictedAsciiRefusesLineBreaksTabsAndBytesOutsidePrintableAscii() {
        val bodies = listOf(
            byteArrayOf(0x0a),
            "a\nb".toByteArray(),
            byteArrayOf(0x09),
            byteArrayOf(0x7f),
            byteArrayOf(0x00),
            "é".toByteArray(Charsets.UTF_8),
        )
        for (body in bodies) {
            assertEquals(SolanaOffchainMessage.NOT_ASCII, rejected(offchainEnvelope(body, format = 0)), "body ${body.toList()}")
        }
    }

    @Test
    fun limitedUtf8RefusesBytesThatAreNotUtf8() {
        val bodies = listOf(
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xff.toByte()),
        )
        for (body in bodies) {
            assertEquals(SolanaOffchainMessage.NOT_UTF8, rejected(offchainEnvelope(body, format = 1)), "body ${body.toList()}")
        }
    }

    /** A character the owner could not read is refused: a control, an invisible format character, private use or unassigned. */
    @Test
    fun limitedUtf8RefusesCharactersTheOwnerCannotRead() {
        val unreadable = listOf("\u0000", "\u0007", "‮", "​", "", "󠀁", "͸")
        for (text in unreadable) {
            val envelope = offchainEnvelope(text.toByteArray(Charsets.UTF_8), format = 1)
            assertEquals(SolanaOffchainMessage.UNSHOWABLE, rejected(envelope), "code point ${text.codePointAt(0)}")
        }
    }

    /** Line breaks, tabs, letters and an emoji are read by eye, so they are signed as text. */
    @Test
    fun limitedUtf8AcceptsTheCharactersTheOwnerCanRead() {
        for (text in listOf("\n", "\r\n", "\t", "Café", "中文", "😀")) {
            val message = parsed(offchainEnvelope(text.toByteArray(Charsets.UTF_8), format = 1))
            assertEquals(text, message.text, "text $text")
        }
    }

    /** Random bytes, and valid messages with one random byte changed, never make the parser throw. Each one is a message or a refusal. */
    @Test
    fun noInputMakesTheParserThrow() {
        val random = Random(1)
        val valid = offchainEnvelope("Sign in\nnonce 7".toByteArray(), format = 1, signers = listOf(key(1), key(2)))
        repeat(3000) { round ->
            val bytes = if (round % 2 == 0) {
                random.nextBytes(random.nextInt(0, 200))
            } else {
                valid.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
            }
            assertIs<SolanaOffchainMessage.Result>(SolanaOffchainMessage.parse(bytes))
        }
    }

    private fun parsed(bytes: ByteArray): SolanaOffchainMessage =
        assertIs<SolanaOffchainMessage.Result.Ok>(SolanaOffchainMessage.parse(bytes)).message

    private fun rejected(bytes: ByteArray): String =
        assertIs<SolanaOffchainMessage.Result.Rejected>(SolanaOffchainMessage.parse(bytes)).reason

    private fun key(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }
}

/**
 * An off-chain message at header [version] 0, as the Solana proposal lays it out: the signing domain, the version, the application
 * [domain], the message [format], the [signers], a little-endian length and the [body]. [declaredLength] replaces the true length.
 */
internal fun offchainEnvelope(
    body: ByteArray,
    format: Int = 0,
    domain: ByteArray = ByteArray(32) { 5 },
    signers: List<ByteArray> = listOf(ByteArray(32) { 1 }),
    version: Int = 0,
    declaredLength: Int = body.size,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0xff.toByte()))
    out.write("solana offchain".toByteArray(Charsets.US_ASCII))
    out.write(version)
    out.write(domain)
    out.write(format)
    out.write(signers.size)
    signers.forEach { out.write(it) }
    out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(declaredLength.toShort()).array())
    out.write(body)
    return out.toByteArray()
}
