package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review and refusal states of the sign flow. The reviews use [snapshotSettled], because whether APPROVE is on depends on
 * measured sizes that take a few frames to settle. The native and outside-registry reviews open at the top, so APPROVE is off there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewNativeTransfer() = compose.snapshotSettled("tx_review_native") {
        TxReviewScreen(review = sampleNativeReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewOutsideRegistry() = compose.snapshotSettled("tx_review_outside_registry") {
        TxReviewScreen(review = sampleUnknownChainReview, onApprove = {}, onReject = {})
    }

    @Test
    fun reviewPersonalMessage() = compose.snapshotSettled("tx_review_message") {
        TxReviewScreen(review = sampleMessageReview, onApprove = {}, onReject = {})
    }

    @Test
    fun refused() = compose.snapshot("sign_refused") {
        SignRefusedScreen(
            reason = "Chain ID mismatch. The request targets chain 1, but this vault is set to chain 10143.",
            onDone = {},
        )
    }
}
