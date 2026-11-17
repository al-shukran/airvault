package app.lensly.ui.sign

import androidx.compose.foundation.ScrollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewReadTest {
    @Test
    fun aListThatFitsOnScreenIsReadOnceSettled() {
        val read = fitting()
        assertFalse(read.read)
        read.settle()
        assertTrue(read.read)
    }

    @Test
    fun aListThatIsNotScrolledToItsEndIsUnread() {
        val read = ReviewRead(ScrollState(0)).apply {
            viewportPx = 300
            contentPx = 900
        }
        read.settle()
        assertFalse(read.read)
    }

    @Test
    fun aReadReviewStaysReadWhenItsListGrows() {
        val read = fitting()
        read.settle()
        read.viewportPx = 300
        read.contentPx = 900
        read.settle()
        assertTrue(read.read)
    }

    @Test
    fun theHintAsksForTheEndUntilTheReviewIsRead() {
        val read = ReviewRead(ScrollState(0))
        assertEquals("Scroll to the end to review everything", readHint(read))
        read.settle()
        assertEquals("Scroll to the end to review everything", readHint(read))
        val fitted = fitting().apply { settle() }
        assertNull(readHint(fitted))
    }

    /** A review whose list fits on screen: the content is shorter than the viewport. */
    private fun fitting(): ReviewRead = ReviewRead(ScrollState(0)).apply {
        viewportPx = 800
        contentPx = 400
    }
}
