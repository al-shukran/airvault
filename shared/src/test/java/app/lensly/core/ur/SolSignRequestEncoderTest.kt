package app.lensly.core.ur

import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class SolSignRequestEncoderTest {

    private val requestId = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")

    // The Keystone sample of SolSignRequestTest: a Solflare transaction at m/44'/501'/0'/0' with source fingerprint 12121212 and no address.
    private val keystoneCbor = "a601d825509b1deb4d3b7d4bad9bdd2b0d7b3dcb6d02589601000103c8d842a2f17fd7aab608ce2ea535a6e958dffa20caf669b347b911c4171965530f957620b228bae2b94c82ddd4c093983a67365555b737ec7ddc1117e61c72e0000000000000000000000000000000000000000000000000000000000000000010295cc2f1f39f3604718496ea00676d6a72ec66ad09d926e3ece34f565f18d201020200010c0200000000e1f5050000000003d90130a20188182cf51901f5f500f500f5021a1212121204400568736f6c666c6172650601"

    /** The UR that the first frames of [frames] complete, read as a scanner reads them. A large request is a fountain of frames. */
    private fun scanned(frames: Sequence<String>): UR {
        val scanner = UrScanner()
        for (frame in frames.take(300)) {
            assertTrue(scanner.receive(frame), frame)
            scanner.result()?.let { return it }
        }
        return assertNotNull(scanner.result(), "the frames did not complete the request")
    }

    private fun keystoneRequest(): SolSignRequest =
        assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(UR(SolSignRequest.UR_TYPE, Numeric.hexStringToByteArray(keystoneCbor)))).request

    @Test
    fun reencodesTheKeystoneSampleExceptItsEmptyAddressField() {
        // The sample sends an empty address, which the encoder leaves out. Everything else is the sample, byte for byte: the map has five
        // fields, not six, and the fingerprint is followed at once by origin (key 5) and sign type (key 6).
        val withoutEmptyAddress = "a5" + keystoneCbor.substring(2).replace(
            "1a1212121204400568736f6c666c6172650601",
            "1a121212120568736f6c666c6172650601",
        )
        assertNotEquals(keystoneCbor, withoutEmptyAddress)

        assertEquals(withoutEmptyAddress, scanned(SolSignRequestEncoder.encode(keystoneRequest())).cborBytes.toHex())
    }

    @Test
    fun framesAreUppercaseSolSignRequestUr() {
        val frame = SolSignRequestEncoder.encode(keystoneRequest()).first()

        assertTrue(frame.startsWith("UR:SOL-SIGN-REQUEST/"), frame)
        assertEquals(frame, frame.uppercase())
    }

    @Test
    fun aRequestWithAnAddressAndOriginDecodesBackToItsFields() {
        val address = ByteArray(32) { (it * 3).toByte() }
        val request = SolSignRequest(
            requestId = requestId,
            signData = ByteArray(40) { 1 },
            derivationPath = listOf(PathStep(44, true), PathStep(501, true), PathStep(7, true)),
            sourceFingerprint = 0x0BADF00D,
            address = address,
            origin = "Lumio",
            signType = SolSignType.TRANSACTION,
        )

        val decoded = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(scanned(SolSignRequestEncoder.encode(request)))).request

        assertContentEquals(requestId, decoded.requestId)
        assertContentEquals(request.signData, decoded.signData)
        assertEquals(request.derivationPath, decoded.derivationPath)
        assertEquals(0x0BADF00D, decoded.sourceFingerprint)
        assertContentEquals(address, decoded.address)
        assertEquals("Lumio", decoded.origin)
        assertEquals(SolSignType.TRANSACTION, decoded.signType)
    }

    @Test
    fun aRequestThatLensWouldRefuseIsNotEncoded() {
        val sign = byteArrayOf(1)
        val transaction = SolSignType.TRANSACTION
        // A step that is not hardened, an account of another coin, and an empty payload are all refused by the decoder.
        val notHardened = SolSignRequest(null, sign, listOf(PathStep(44, true), PathStep(501, true), PathStep(0, false)), null, null, null, transaction)
        val otherCoin = SolSignRequest(null, sign, listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true)), null, null, null, transaction)
        val empty = SolSignRequest(null, ByteArray(0), listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)), null, null, null, transaction)

        assertFailsWith<IllegalArgumentException> { SolSignRequestEncoder.encode(notHardened) }
        assertFailsWith<IllegalArgumentException> { SolSignRequestEncoder.encode(otherCoin) }
        assertFailsWith<IllegalArgumentException> { SolSignRequestEncoder.encode(empty) }
    }
}
