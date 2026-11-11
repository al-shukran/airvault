package app.lensly.fuzz

import app.lensly.core.ur.EthSignRequest
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * A scanned `ur:eth-sign-request` payload comes from a QR code, so its CBOR is fully attacker-controlled.
 * [EthSignRequest.decode] documents that it never throws: every failure arrives as [EthSignRequest.Result.Rejected].
 *
 * The target asserts only that contract. It says nothing about which payloads are accepted.
 */
class EthSignRequestFuzzTest {

    @MethodSource("seeds")  // Jazzer requires @FuzzTest to be the last annotation on the method.
    @FuzzTest(maxDuration = "15m")
    fun ethSignRequestDecodeNeverThrows(bytes: ByteArray) {
        // Exhaustive over the sealed result: Ok and Rejected are both fine, a new branch forces this to be revisited.
        when (EthSignRequest.decode(bytes)) {
            is EthSignRequest.Result.Ok -> Unit
            is EthSignRequest.Result.Rejected -> Unit
        }
    }

    companion object {
        @JvmStatic
        fun seeds(): List<ByteArray> = FuzzSeeds.bytes("eth-sign-request")
    }
}
