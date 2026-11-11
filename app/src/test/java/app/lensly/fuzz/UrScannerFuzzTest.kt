package app.lensly.fuzz

import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.UrScanner
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * [UrScanner] receives one QR payload at a time, straight from a camera. It documents that a part it does not
 * accept comes back as `false`; it must never throw, hang, or allocate memory for a frame count it declares.
 *
 * Each fuzz input is fed as a single part, as the frames of an animated UR, and as a part that declares more frames
 * than the sandbox allows. Nothing is asserted about the scanned content.
 */
class UrScannerFuzzTest {

    @MethodSource("seeds")  // Jazzer requires @FuzzTest to be the last annotation on the method.
    @FuzzTest(maxDuration = "15m")
    fun scannedFramesNeverEscapeAnException(input: ByteArray) {
        if (input.size > ParserLimits.MAX_UR_PART_CHARS) return
        val body = String(input, Charsets.ISO_8859_1)

        receiveAll(UrScanner(), listOf("ur:eth-sign-request/$body"))

        // The same body split into up to three declared frames, as an animated UR arrives.
        val frames = splitIntoFrames(body)
        receiveAll(UrScanner(), frames)

        // A frame that declares far more parts than ParserLimits.MAX_UR_PARTS must be refused before the decoder
        // builds one index per declared part.
        receiveAll(UrScanner(), listOf("ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS + 1}/$body"))
    }

    private fun receiveAll(scanner: UrScanner, parts: List<String>) {
        for (part in parts) {
            scanner.receive(part)
            scanner.progress
        }
        scanner.result()
    }

    private fun splitIntoFrames(body: String): List<String> {
        val chunk = maxOf(1, (body.length + 2) / 3)
        val chunks = body.chunked(chunk).ifEmpty { listOf("") }
        return chunks.mapIndexed { index, part -> "ur:eth-sign-request/${index + 1}-${chunks.size}/$part" }
    }

    companion object {
        @JvmStatic
        fun seeds(): List<ByteArray> = FuzzSeeds.bytes("ur-scanner")
    }
}
