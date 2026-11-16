package app.lensly.core.ur

import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class SolSignatureEncoderTest {

    private val requestId = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")

    // The sol-signature CBOR sample and its signature, copied from KeystoneHQ/keystone-sdk-web src/__tests__/solana.test.ts
    // (parseSignature, commit 7192bd95).
    private val keystoneCbor = "a201d825509b1deb4d3b7d4bad9bdd2b0d7b3dcb6d025840d4f0a7bcd95bba1fbb1051885054730e3f47064288575aacc102fbbf6a9a14daa066991e360d3e3406c20c00a40973eff37c7d641e5b351ec4a99bfe86f335f7"
    private val signature = Numeric.hexStringToByteArray("d4f0a7bcd95bba1fbb1051885054730e3f47064288575aacc102fbbf6a9a14daa066991e360d3e3406c20c00a40973eff37c7d641e5b351ec4a99bfe86f335f7")

    private fun scanned(frame: String): UR {
        val scanner = UrScanner()
        assertTrue(scanner.receive(frame), frame)
        return assertNotNull(scanner.result())
    }

    /** The 8-4-4-4-12 form of a 16-byte UUID. */
    private fun uuid(bytes: ByteArray): String {
        val hex = bytes.toHex()
        return listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20))
            .joinToString("-")
    }

    @Test
    fun encodesTheKeystoneSampleByteForByte() {
        val frame = SolSignatureEncoder.encode(requestId, signature).first()

        assertEquals(keystoneCbor, scanned(frame).cborBytes.toHex())
    }

    @Test
    fun theSampleDecodesToItsRequestIdAndSignature() {
        val root = Cbor.decode(Numeric.hexStringToByteArray(keystoneCbor)) as Cbor.Dict
        assertEquals(2, root.entries.size)

        val echoedId = root.entries[0].second as Cbor.Tagged
        assertEquals(37L, echoedId.tag)
        assertContentEquals(requestId, (echoedId.value as Cbor.Bytes).value)
        assertEquals("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", uuid(requestId))

        assertContentEquals(signature, (root.entries[1].second as Cbor.Bytes).value)
        assertEquals(64, signature.size)
    }

    @Test
    fun frameIsUppercaseSolSignatureUr() {
        val frame = SolSignatureEncoder.encode(requestId, signature).first()

        assertTrue(frame.startsWith("UR:SOL-SIGNATURE/"), frame)
        assertEquals(frame, frame.uppercase())
    }

    @Test
    fun animatedFramesDecodeBackToTheSignature() {
        val frames = SolSignatureEncoder.encode(requestId, signature, maxFragment = 20)
        assertTrue(frames.first().startsWith("UR:SOL-SIGNATURE/1-"), frames.first())

        val scanner = UrScanner()
        for (frame in frames.take(300)) {
            scanner.receive(frame)
            if (scanner.result() != null) break
        }
        assertEquals(keystoneCbor, assertNotNull(scanner.result()).cborBytes.toHex())
    }

    @Test
    fun rejectsOutOfSpecInputs() {
        assertFailsWith<IllegalArgumentException> { SolSignatureEncoder.encode(requestId.copyOf(15), signature) }
        assertFailsWith<IllegalArgumentException> { SolSignatureEncoder.encode(requestId, signature.copyOf(63)) }
        assertFailsWith<IllegalArgumentException> { SolSignatureEncoder.encode(requestId, signature.copyOf(65)) }
    }
}
