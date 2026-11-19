package app.lensly.ui.home

import kotlin.test.assertEquals
import org.junit.Test

class HistoryOriginTest {

    @Test
    fun aPrintableOriginShowsAsItIs() {
        assertEquals("app.example", historyOrigin("app.example"))
        assertEquals("a\nb", historyOrigin("a\nb"))
    }

    /** U+202E is a right-to-left override. It is not printable, so the origin shows its UTF-8 bytes in hex: 61, E2 80 AE, 62. */
    @Test
    fun aRightToLeftOverrideShowsTheBytesOfTheOrigin() {
        assertEquals("61e280ae62", historyOrigin("a‮b"))
    }

    @Test
    fun aControlCharacterShowsTheBytesOfTheOrigin() {
        assertEquals("6107", historyOrigin("a\u0007"))
    }
}
