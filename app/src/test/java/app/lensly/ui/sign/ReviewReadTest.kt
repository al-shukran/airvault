package app.lensly.ui.sign

import androidx.compose.foundation.ScrollState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewReadTest {

    @Test
    fun aReviewWithNoWarningIsReadWhenItsListIsReached() {
        assertTrue(isReviewRead(hasWarnings = false, detailsOpened = false, listReached = true))
        assertFalse(isReviewRead(hasWarnings = false, detailsOpened = false, listReached = false))
    }

    @Test
    fun aReviewWithAWarningIsNotReadByItsListOrByItsDetailsAlone() {
        assertFalse(isReviewRead(hasWarnings = true, detailsOpened = false, listReached = true))
        assertFalse(isReviewRead(hasWarnings = true, detailsOpened = true, listReached = false))
    }

    @Test
    fun aReviewWithAWarningIsReadOnceItsDetailsAreOpenedAndItsListIsReached() {
        assertTrue(isReviewRead(hasWarnings = true, detailsOpened = true, listReached = true))
    }

    /** A read state whose list fits on screen, so the list is reached without a scroll. */
    private fun fitting(): ReviewRead = ReviewRead(ScrollState(0)).apply {
        viewportPx = 900
        contentPx = 400
    }

    @Test
    fun aWarningReviewThatFitsSaysToOpenItsDetailsFirstAndIsReadOnceOpened() {
        val read = fitting()
        read.settle(hasWarnings = true)
        assertFalse(read.read)
        assertEquals("Open details to continue", readHint(read, hasWarnings = true))

        read.openDetails()
        read.settle(hasWarnings = true)
        assertTrue(read.read)
        assertNull(readHint(read, hasWarnings = true))
    }

    @Test
    fun aReviewWithNoWarningThatFitsIsReadAndNeedsNoHint() {
        val read = fitting()
        read.settle(hasWarnings = false)
        assertTrue(read.read)
        assertNull(readHint(read, hasWarnings = false))
    }

    @Test
    fun aReviewThatIsNotReadAsksToBeScrolledToItsEnd() {
        val read = ReviewRead(ScrollState(0))
        read.settle(hasWarnings = false)
        assertEquals("Scroll to the end to review everything", readHint(read, hasWarnings = false))
    }
}
