package app.lensly.core.ur

import app.lensly.app.export.partsOf
import com.sparrowwallet.hummingbird.UR
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class EthSignatureDecoderTest {
    private val requestId = ByteArray(16) { (0x40 + it).toByte() }
    private val signature = ByteArray(65) { (it * 5 + 1).toByte() }.also { it[64] = 1 }

    private fun scanned(parts: List<String>): UR {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertNotNull(scanner.result())
    }

    /** The eth-signature UR of a payload with the given fields, as a camera would read it. */
    private fun urOf(vararg fields: Pair<Long, Cbor>): UR = UR(EthSignatureEncoder.UR_TYPE, Cbor.encode(Cbor.Dict(fields.map { Cbor.Uint(it.first) to it.second })))

    @Test
    fun anEncodedSignatureReadsBackItsRequestIdAndSignature() {
        val parsed = assertIs<EthSignature.Result.Ok>(
            EthSignature.decode(scanned(partsOf(EthSignatureEncoder.encode(requestId, signature)))),
        ).signature

        assertContentEquals(requestId, parsed.requestId)
        assertContentEquals(signature, parsed.signature)
        assertEquals(EthSignatureEncoder.DEFAULT_ORIGIN, parsed.origin)
    }

    @Test
    fun aUrOfAnotherTypeIsRejected() {
        val ur = UR(EthSignRequest.UR_TYPE, Cbor.encode(Cbor.Dict(emptyList())))

        assertIs<EthSignature.Result.Rejected>(EthSignature.decode(ur))
    }

    @Test
    fun aRequestIdOfTheWrongLengthIsRejected() {
        val ur = urOf(1L to Cbor.Tagged(37L, Cbor.Bytes(ByteArray(15))), 2L to Cbor.Bytes(signature))

        assertIs<EthSignature.Result.Rejected>(EthSignature.decode(ur))
    }

    @Test
    fun aMissingRequestIdIsRejected() {
        assertIs<EthSignature.Result.Rejected>(EthSignature.decode(urOf(2L to Cbor.Bytes(signature))))
    }

    @Test
    fun aSignatureOutsideTheLengthRangeIsRejected() {
        for (size in listOf(64, 81)) {
            val ur = urOf(1L to Cbor.Tagged(37L, Cbor.Bytes(requestId)), 2L to Cbor.Bytes(ByteArray(size)))
            assertIs<EthSignature.Result.Rejected>(EthSignature.decode(ur), "signature of $size bytes")
        }
    }

    @Test
    fun randomPayloadsAreRejectedOrReadWithoutThrowing() {
        val random = Random(7)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(96)) { random.nextInt().toByte() }
            val result = EthSignature.decode(UR(EthSignatureEncoder.UR_TYPE, bytes))
            assertTrue(result is EthSignature.Result.Ok || result is EthSignature.Result.Rejected)
        }
    }
}
