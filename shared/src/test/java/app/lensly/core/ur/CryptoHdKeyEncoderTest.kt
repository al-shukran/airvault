package app.lensly.core.ur

import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class CryptoHdKeyEncoderTest {

    private val pubkey = Numeric.hexStringToByteArray("02eae4b876a8696134b868f88cc2f51f715f2dbedb7446b8e6edf3d4541c4eb67b")
    private val chainCode = Numeric.hexStringToByteArray("d882718b7a42806803eeb17f7483f20620611adb88fc943c898dc5aba94c2819")
    private val masterFp = 0xe9181cf3.toInt()
    private val parentFp = 0xd32e4508.toInt()

    @Test
    fun cborMatchesKeystoneVectorWithoutName() {
        // Keystone CryptoHDKey vector (ur-registry-eth __tests__/CryptoHDKey.test.ts): keys 3, 4, 6 and 8 only.
        val expected = "a403582102eae4b876a8696134b868f88cc2f51f715f2dbedb7446b8e6edf3d4541c4eb67b" +
            "045820d882718b7a42806803eeb17f7483f20620611adb88fc943c898dc5aba94c2819" +
            "06d90130a20186182cf5183cf500f5021ae9181cf3081ad32e4508"
        val cbor = CryptoHdKeyEncoder.ur(pubkey, chainCode, masterFp, parentFp, name = null).cborBytes
        assertEquals(expected, cbor.toHex())
    }

    @Test
    fun cborLayoutMatchesSpecWithLensly() {
        // Wire spec layout: a5 03 5821 <key> 04 5820 <cc> 06 d90130 a2 01 86 182c f5 183c f5 00 f5 02 1a <fp>
        // 08 1a <parent fp> 09 <name>. Name "Lensly" is 6 text bytes: 66 4c656e736c79.
        val expected = "a5035821" + pubkey.toHex() +
            "045820" + chainCode.toHex() +
            "06d90130a20186182cf5183cf500f5021ae9181cf3081ad32e4508" +
            "09664c656e736c79"
        val cbor = CryptoHdKeyEncoder.ur(pubkey, chainCode, masterFp, parentFp, "Lensly").cborBytes
        assertEquals(expected, cbor.toHex())
    }

    @Test
    fun singleFrameRoundTripsThroughScannerAndRegistry() {
        val frame = CryptoHdKeyEncoder.encode(pubkey, chainCode, masterFp, parentFp, "Lensly")
        assertTrue(frame.startsWith("UR:CRYPTO-HDKEY/"))

        val scanner = UrScanner()
        assertTrue(scanner.receive(frame))
        val ur = assertNotNull(scanner.result())
        assertEquals("crypto-hdkey", ur.type)

        val key = ur.decodeFromRegistry() as CryptoHDKey
        assertContentEquals(pubkey, key.key)
        assertContentEquals(chainCode, key.chainCode)
        assertContentEquals(fingerprint(parentFp), key.parentFingerprint)
        assertContentEquals(fingerprint(masterFp), key.origin.sourceFingerprint)
        assertEquals("Lensly", key.name)
        assertNull(key.children)
        assertEquals(listOf(44, 60, 0), key.origin.components.map { (it as IndexPathComponent).index })
        assertEquals(listOf(true, true, true), key.origin.components.map { (it as IndexPathComponent).isHardened })
    }

    @Test
    fun payloadThatFitsOneFrameIsEmittedAsOneFrame() {
        val single = CryptoHdKeyEncoder.encode(pubkey, chainCode, masterFp, parentFp, "Lensly")
        assertEquals(single, CryptoHdKeyEncoder.urParts(pubkey, chainCode, masterFp, parentFp, "Lensly").first())
    }

    @Test
    fun animatedFramesDecodeBackToTheSamePayload() {
        val expected = CryptoHdKeyEncoder.ur(pubkey, chainCode, masterFp, parentFp, "Lensly").cborBytes
        val frames = CryptoHdKeyEncoder.urParts(pubkey, chainCode, masterFp, parentFp, "Lensly", maxFragment = 40)
        assertTrue(frames.first().startsWith("UR:CRYPTO-HDKEY/1-"))

        val scanner = UrScanner()
        for (frame in frames.take(200)) {
            scanner.receive(frame)
            if (scanner.result() != null) break
        }
        val ur = assertNotNull(scanner.result())
        assertContentEquals(expected, ur.cborBytes)
        assertEquals(1.0, scanner.progress, 0.0)
    }

    @Test
    fun rejectsWrongKeyLengths() {
        assertFailsWith<IllegalArgumentException> {
            CryptoHdKeyEncoder.encode(pubkey.copyOf(32), chainCode, masterFp, parentFp, "Lensly")
        }
        assertFailsWith<IllegalArgumentException> {
            CryptoHdKeyEncoder.encode(pubkey, chainCode.copyOf(31), masterFp, parentFp, "Lensly")
        }
    }

    private fun fingerprint(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
}
