package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class SolSignatureTest {

    private val requestIdHex = "9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d"
    private val requestId = Numeric.hexStringToByteArray(requestIdHex)
    private val signatureHex = "d4f0a7bcd95bba1fbb1051885054730e3f47064288575aacc102fbbf6a9a14daa066991e360d3e3406c20c00a40973eff37c7d641e5b351ec4a99bfe86f335f7"
    private val signature = Numeric.hexStringToByteArray(signatureHex)

    // The Keystone sol-signature sample of SolSignatureEncoderTest: key 1 is the tagged request ID, key 2 the 64-byte signature.
    private val keystoneCbor = "a201d825509b1deb4d3b7d4bad9bdd2b0d7b3dcb6d025840$signatureHex"

    private fun ur(cborHex: String): UR = UR(SolSignatureEncoder.UR_TYPE, Numeric.hexStringToByteArray(cborHex))

    private fun rejected(cborHex: String): String = assertIs<SolSignature.Result.Rejected>(SolSignature.decode(ur(cborHex))).reason

    @Test
    fun theKeystoneSampleDecodesToItsRequestIdAndSignature() {
        val decoded = assertIs<SolSignature.Result.Ok>(SolSignature.decode(ur(keystoneCbor))).signature

        assertContentEquals(requestId, decoded.requestId)
        assertContentEquals(signature, decoded.signature)
    }

    @Test
    fun anEncoderFrameDecodesBackThroughTheScanner() {
        val scanner = UrScanner()
        val frame = SolSignatureEncoder.encode(requestId, signature).first()
        assertTrue(scanner.receive(frame))
        val scanned = assertNotNull(scanner.result())

        val decoded = assertIs<SolSignature.Result.Ok>(SolSignature.decode(scanned)).signature

        assertContentEquals(requestId, decoded.requestId)
        assertContentEquals(signature, decoded.signature)
    }

    @Test
    fun aRequestIdWithoutItsTagIsAccepted() {
        val untagged = "a201" + "50" + requestIdHex + "025840" + signatureHex

        val decoded = assertIs<SolSignature.Result.Ok>(SolSignature.decode(ur(untagged))).signature

        assertContentEquals(requestId, decoded.requestId)
    }

    @Test
    fun otherUrTypesAreRejected() {
        val request = UR("sol-sign-request", Numeric.hexStringToByteArray(keystoneCbor))

        assertTrue(assertIs<SolSignature.Result.Rejected>(SolSignature.decode(request)).reason.contains("is not sol-signature"))
    }

    @Test
    fun aMalformedPayloadIsRejected() {
        // Not a map.
        assertTrue(rejected("43010203").contains("not a CBOR map"))
        // The signature is missing.
        assertTrue(rejected("a1" + "01" + "d825" + "50" + requestIdHex).contains("signature"))
        // A 63-byte signature.
        assertTrue(rejected("a201" + "d825" + "50" + requestIdHex + "02" + "583f" + signatureHex.substring(0, 126)).contains("64 bytes"))
        // A 15-byte request ID.
        assertTrue(rejected("a201" + "d825" + "4f" + requestIdHex.substring(0, 30) + "025840" + signatureHex).contains("16 bytes"))
        // The same key twice.
        assertTrue(rejected("a301" + "50" + requestIdHex + "01" + "50" + requestIdHex + "025840" + signatureHex).contains("duplicate"))
    }
}
