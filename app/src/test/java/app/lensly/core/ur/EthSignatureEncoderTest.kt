package app.lensly.core.ur

import app.lensly.core.wallet.toHex
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class EthSignatureEncoderTest {

    private val requestId = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")

    // r || s || v from the Keystone EthSignature vector.
    private val signature = Numeric.hexStringToByteArray(
        "d4f0a7bcd95bba1fbb1051885054730e3f47064288575aacc102fbbf6a9a14daa066991e360d3e3406c20c00a40973eff37c7d641e5b351ec4a99bfe86f335f713",
    )

    @Test
    fun singleFrameMatchesKeystoneUrByteForByte() {
        val frame = EthSignatureEncoder.encode(requestId, signature, origin = "keystone").first()
        assertEquals(KeystoneVectors.ur("eth-signature.txt"), frame.lowercase())
    }

    @Test
    fun frameIsUppercaseUr() {
        val frame = EthSignatureEncoder.encode(requestId, signature).first()
        assertTrue(frame.startsWith("UR:ETH-SIGNATURE/"), frame)
        assertEquals(frame, frame.uppercase())
    }

    @Test
    fun originDefaultsToLensly() {
        val scanner = UrScanner()
        assertTrue(scanner.receive(EthSignatureEncoder.encode(requestId, signature).first()))
        val cbor = assertNotNull(scanner.result()).cborBytes
        // Key 3 is a text string of six bytes: 03 66 "Lensly".
        assertTrue(cbor.toHex().endsWith("03664c656e736c79"), cbor.toHex())
    }

    @Test
    fun animatedFramesDecodeBackToTheSignature() {
        val frames = EthSignatureEncoder.encode(requestId, signature, origin = "x".repeat(200), maxFragment = 60)
        assertTrue(frames.first().startsWith("UR:ETH-SIGNATURE/1-"))

        val scanner = UrScanner()
        for (frame in frames.take(200)) {
            scanner.receive(frame)
            if (scanner.result() != null) break
        }
        val root = Cbor.decode(assertNotNull(scanner.result()).cborBytes) as Cbor.Dict
        val echoedId = (root.entries[0].second as Cbor.Tagged).value as Cbor.Bytes
        val echoedSignature = root.entries[1].second as Cbor.Bytes
        assertContentEquals(requestId, echoedId.value)
        assertContentEquals(signature, echoedSignature.value)
    }

    @Test
    fun rejectsOutOfSpecInputs() {
        assertFailsWith<IllegalArgumentException> { EthSignatureEncoder.encode(requestId.copyOf(15), signature) }
        assertFailsWith<IllegalArgumentException> { EthSignatureEncoder.encode(requestId, signature.copyOf(64)) }
    }
}
