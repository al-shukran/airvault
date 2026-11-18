package app.lensly.ui.qr

import kotlinx.coroutines.runBlocking

/**
 * Encodes [parts] into the frame cache before a screenshot composes the screen that shows them, so [AnimatedQr] draws the
 * codes on its first frame. [AnimatedQr] encodes off the main thread and draws the result after a recomposition. The
 * screenshot tests run a paused clock, and that recomposition does not reach the screen before the capture, so the golden
 * would show a blank card. Call it before `snapshot` with the same parts and [keepCase] as the screen.
 */
fun primeQrCodes(parts: List<String>, keepCase: Boolean = false) {
    runBlocking { QrFrameCache.put(parts, keepCase, encodeQrFrames(parts, keepCase)) }
}
