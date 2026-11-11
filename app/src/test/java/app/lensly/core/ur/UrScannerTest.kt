package app.lensly.core.ur

import app.lensly.core.parse.ParserLimits
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class UrScannerTest {

    private val pubkey = Numeric.hexStringToByteArray("02eae4b876a8696134b868f88cc2f51f715f2dbedb7446b8e6edf3d4541c4eb67b")
    private val chainCode = Numeric.hexStringToByteArray("d882718b7a42806803eeb17f7483f20620611adb88fc943c898dc5aba94c2819")

    @Test
    fun rejectsTextThatIsNotUr() {
        val scanner = UrScanner()
        assertFalse(scanner.receive("hello"))
        assertNull(scanner.result())
        assertEquals(0.0, scanner.progress, 0.0)
    }

    @Test
    fun acceptsSingleFrameInEitherCase() {
        val upper = KeystoneVectors.ur("eth-signature.txt").uppercase()
        val scanner = UrScanner()
        assertTrue(scanner.receive(upper))
        assertNotNull(scanner.result())
        assertEquals(1.0, scanner.progress, 0.0)
    }

    @Test
    fun ignoresFramesOnceAUrIsComplete() {
        val scanner = UrScanner()
        assertTrue(scanner.receive(KeystoneVectors.ur("eth-signature.txt")))
        val first = scanner.result()
        assertFalse(scanner.receive(KeystoneVectors.ur("eth-sign-request-origin.txt")))
        assertSame(first, scanner.result())
    }

    @Test
    fun progressRisesToOneAcrossAnimatedFrames() {
        val frames = CryptoHdKeyEncoder.urParts(pubkey, chainCode, 0x12345678, 0x0badf00d, "Lensly", maxFragment = 40)
        val scanner = UrScanner()
        var last = 0.0
        for (frame in frames.take(200)) {
            assertTrue(scanner.receive(frame))
            assertTrue(scanner.progress >= last, "progress fell from $last to ${scanner.progress}")
            last = scanner.progress
            if (scanner.result() != null) break
        }
        assertNotNull(scanner.result())
        assertEquals(1.0, scanner.progress, 0.0)
    }

    @Test
    fun refusesAPartThatDeclaresMoreThanTheLimitOfParts() {
        val declared = "ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS + 1}/aa"
        assertEquals("The request has more than ${ParserLimits.MAX_UR_PARTS} parts.", urPartRefusal(declared))
        assertFalse(UrScanner().receive(declared))
    }

    @Test
    fun aPartDeclaringAnAbsurdCountIsRefusedBeforeTheDecoderBuildsItsIndexes() {
        // The decoder keeps one index per declared part, so these must never reach it.
        for (absurd in listOf("ur:eth-sign-request/1-2147483647/aa", "ur:eth-sign-request/1-99999999999999999999/aa")) {
            assertNotNull(urPartRefusal(absurd), absurd)
            assertFalse(UrScanner().receive(absurd), absurd)
        }
    }

    @Test
    fun acceptsPartsAtTheCountLimitAndTheRealVectors() {
        assertNull(urPartRefusal("ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS}/aa"))
        assertNull(urPartRefusal(KeystoneVectors.ur("eth-sign-request-origin.txt")))
    }

    @Test
    fun refusesAPartLongerThanTheCharacterLimit() {
        val long = "ur:eth-sign-request/1-2/" + "a".repeat(ParserLimits.MAX_UR_PART_CHARS)
        assertEquals("A code is longer than ${ParserLimits.MAX_UR_PART_CHARS} characters.", urPartRefusal(long))
        assertFalse(UrScanner().receive(long))
    }

    @Test
    fun leavesTextThatIsNotAUrPartToTheDecoder() {
        assertNull(urPartRefusal("https://example.com/" + "a".repeat(5000)))
        assertFalse(UrScanner().receive("https://example.com/" + "a".repeat(5000)))
    }
}
