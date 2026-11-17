package app.lensly.ui.sign

import app.lensly.app.SigningAccount

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp

/** Swipes that run a review's list to its end. A review holds one scrolling list, so the swipes go to it. */
private const val SCROLL_SWIPES = 12

/**
 * Swipes the review's list up until it is at its end, the way the owner scrolls it. Each swipe covers most of the list, so the swipes are
 * enough for any review the tests build. A list that fits on screen has nothing to scroll, so it is not read by this.
 */
internal fun ComposeContentTestRule.scrollReviewToEnd() {
    repeat(SCROLL_SWIPES) {
        onNode(hasScrollAction()).performTouchInput { swipeUp() }
    }
    waitForIdle()
}

/** The account that signs the sample reviews: its name, its wallet and its address. */
internal val SAMPLE_SIGNER = SigningAccount(name = "OfPixelBlaze", walletName = "Seed wallet", address = "bc1qtestsigner0000000000000000000000000000")
