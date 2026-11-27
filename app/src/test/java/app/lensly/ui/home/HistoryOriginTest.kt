package app.lensly.ui.home

import kotlin.test.assertEquals
import org.junit.Test

class HistoryOriginTest {

    @Test
    fun aPrintableOriginShowsAsItIs() {
        assertEquals("app.example", historyOrigin("app.example"))
    }

    /** A line break or a tab could start a row that looks like another line of the sheet, so the origin shows its bytes: 61, 0A, 62. */
    @Test
    fun aLineBreakOrATabShowsTheBytesOfTheOrigin() {
        assertEquals("610a62", historyOrigin("a\nb"))
        assertEquals("610962", historyOrigin("a\tb"))
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

    /** U+E0041 is a tag character. It draws nothing, so the origin shows its UTF-8 bytes in hex: 61, F3 A0 81 81. */
    @Test
    fun anInvisibleTagCharacterShowsTheBytesOfTheOrigin() {
        assertEquals("61f3a08181", historyOrigin("a" + String(Character.toChars(0xE0041))))
    }
}
