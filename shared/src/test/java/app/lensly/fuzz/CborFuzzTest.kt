package app.lensly.fuzz

import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.MalformedPayloadException
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * CBOR decoding and re-encoding must stay inside the Parser Sandbox: no unexpected exception, no hang, no
 * allocation that grows with an attacker-declared length.
 *
 * The target asserts nothing about the decoded value. [MalformedPayloadException] is the documented refusal and is
 * expected; every other exception fails the test.
 */
class CborFuzzTest {

    @MethodSource("seeds")  // Jazzer requires @FuzzTest to be the last annotation on the method.
    @FuzzTest(maxDuration = "15m")
    fun cborDecodeRefusesOrSucceeds(bytes: ByteArray) {
        // Production never hands CBOR a payload larger than a sign request, so larger fuzz inputs are not interesting.
        if (bytes.size > ParserLimits.MAX_SIGN_DATA_BYTES) return
        val item = try {
            Cbor.decode(bytes)
        } catch (e: MalformedPayloadException) {
            return
        }
        // Re-encoding a decoded item is the other direction of the same parser and must stay total.
        Cbor.encode(item)
    }

    companion object {
        @JvmStatic
        fun seeds(): List<ByteArray> = FuzzSeeds.bytes("cbor")
    }
}
