package app.lensly.ui.qr

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Waits until the QR code on screen is encoded. [AnimatedQr] encodes off the main thread, so a capture taken straight
 * after the first frame would show a blank card. Use it as the `beforeCapture` of a screenshot of a screen with a code.
 */
fun ComposeContentTestRule.awaitQrCodes() {
    waitUntil(timeoutMillis = 10_000L) {
        onAllNodes(hasContentDescription("QR code", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
}
