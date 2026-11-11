package app.lensly.ui.components

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waits until the in-app keyboard has slid out. Its keys leave the tree when the slide ends, and no key is left to find. The DONE key
 * is the one a test can name, since every screen with the keyboard has one.
 */
fun ComposeTestRule.waitUntilKeyboardHidden() {
    waitUntil(timeoutMillis = 2_000L) {
        onAllNodes(hasContentDescription("Done")).fetchSemanticsNodes().isEmpty()
    }
}
