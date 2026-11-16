package app.lensly.core.ur

import app.lensly.app.export.partsOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AlgoSignatureTest {

    private val requestId = ByteArray(16) { (0x40 + it).toByte() }
    private val signature = ByteArray(64) { (it + 7).toByte() }
    private val publicKey = ByteArray(32) { (0x80 + it).toByte() }

    private fun scanned(parts: List<String>): UR {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertNotNull(scanner.result())
    }

    @Test
    fun aSignatureReadsBackWithItsPublicKey() {
        val parts = partsOf(AlgoSignatureEncoder.encode(requestId, signature, publicKey))

        val parsed = okOf(AlgoSignature.decode(scanned(parts)))

        assertContentEquals(requestId, parsed.requestId)
        assertContentEquals(signature, parsed.signature)
        assertContentEquals(publicKey, parsed.publicKey)
    }

    @Test
    fun thePublicKeyIsOptional() {
        val parsed = okOf(AlgoSignature.decode(scanned(partsOf(AlgoSignatureEncoder.encode(requestId, signature)))))

        assertContentEquals(signature, parsed.signature)
        assertNull(parsed.publicKey)
    }

    @Test
    fun theFrameIsUppercaseAlgoSignatureUr() {
        val frame = AlgoSignatureEncoder.encode(requestId, signature).first()

        assertTrue(frame.startsWith("UR:ALGO-SIGNATURE/"), frame)
        assertEquals(frame, frame.uppercase())
    }

    @Test
    fun animatedFramesReadBack() {
        val parts = partsOf(AlgoSignatureEncoder.encode(requestId, signature, publicKey, maxFragment = 20))

        assertTrue(parts.size > 1)
        assertContentEquals(signature, okOf(AlgoSignature.decode(scanned(parts))).signature)
    }

    @Test
    fun aSignatureThatIsNotSixtyFourBytesIsNotEncoded() {
        assertFailsWith<IllegalArgumentException> { AlgoSignatureEncoder.encode(requestId, signature.copyOf(63)) }
        assertFailsWith<IllegalArgumentException> { AlgoSignatureEncoder.encode(requestId, signature.copyOf(65)) }
        assertFailsWith<IllegalArgumentException> { AlgoSignatureEncoder.encode(requestId.copyOf(15), signature) }
        assertFailsWith<IllegalArgumentException> { AlgoSignatureEncoder.encode(requestId, signature, ByteArray(31)) }
    }

    @Test
    fun aSignatureThatIsNotSixtyFourBytesIsRefusedWhenDecoded() {
        for (size in listOf(63, 65)) {
            assertTrue(rejectedReason(payload(2L to Cbor.Bytes(ByteArray(size)))).contains("64 bytes"), "$size bytes")
        }
    }

    @Test
    fun aPublicKeyThatIsPresentIsThirtyTwoBytes() {
        for (size in listOf(0, 31, 33)) {
            assertTrue(rejectedReason(payload(3L to Cbor.Bytes(ByteArray(size)))).contains("32 bytes"), "$size bytes")
        }
    }

    @Test
    fun requestIdAndSignatureAreRequired() {
        assertTrue(rejectedReason(payload(1L to null)).contains("requestId is missing"))
        assertTrue(rejectedReason(payload(2L to null)).contains("signature is missing"))
    }

    @Test
    fun aRequestIdIsSixteenBytesUnderTag37() {
        assertTrue(rejectedReason(payload(1L to Cbor.Tagged(37, Cbor.Bytes(ByteArray(15))))).contains("16 bytes"))
        assertTrue(rejectedReason(payload(1L to Cbor.Tagged(38, Cbor.Bytes(requestId)))).contains("tag 37"))
        assertTrue(rejectedReason(payload(1L to Cbor.Bytes(requestId))).contains("tag 37"))
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        assertIs<AlgoSignature.Result.Ok>(AlgoSignature.decode(UR("algo-signature", payload())))
        assertIs<AlgoSignature.Result.Rejected>(AlgoSignature.decode(UR("algo-sign-request", payload())))
        assertIs<AlgoSignature.Result.Rejected>(AlgoSignature.decode(UR("eth-signature", payload())))
    }

    @Test
    fun malformedCborIsRefusedAndNoInputThrows() {
        val valid = payload()
        val cases = listOf(
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            byteArrayOf(),
            byteArrayOf(0x9f.toByte()),
        )
        for (bytes in cases) {
            assertIs<AlgoSignature.Result.Rejected>(AlgoSignature.decode(UR("algo-signature", bytes)))
        }

        val random = java.util.Random(13)
        repeat(2000) {
            val copy = valid.copyOf(random.nextInt(valid.size + 1))
            if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = random.nextInt(256).toByte()
            AlgoSignature.decode(UR("algo-signature", copy))
        }
    }

    private fun okOf(result: AlgoSignature.Result): AlgoSignature = assertIs<AlgoSignature.Result.Ok>(result).signature

    private fun rejectedReason(bytes: ByteArray): String =
        assertIs<AlgoSignature.Result.Rejected>(AlgoSignature.decode(UR("algo-signature", bytes))).reason

    /** A valid signature payload, keyed 1 to 3. A null in [changes] removes that key. */
    private fun payload(vararg changes: Pair<Long, Cbor?>): ByteArray {
        val fields = linkedMapOf<Long, Cbor>(
            1L to Cbor.Tagged(37, Cbor.Bytes(requestId)),
            2L to Cbor.Bytes(signature),
            3L to Cbor.Bytes(publicKey),
        )
        for ((key, value) in changes) {
            if (value == null) fields.remove(key) else fields[key] = value
        }
        return Cbor.encode(Cbor.Dict(fields.map { (key, value) -> Cbor.Uint(key) to value }))
    }
}
