package app.airvault.chains.evm.signer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrintableOrHexTest {
    @Test
    fun aLineBreakOrTabInASignedStringIsShownEscapedOnOneLine() {
        assertEquals("first\\nsecond\\r\\tthird", printableOrHex("first\nsecond\r\tthird"))
    }

    @Test
    fun aPrintableStringIsShownAsItIs() {
        assertEquals("plain text", printableOrHex("plain text"))
    }

    @Test
    fun aLineBreakAndABackslashFollowedByNShowDifferently() {
        assertEquals("a\\nb", printableOrHex("a\nb"))
        assertEquals("a\\\\nb", printableOrHex("a\\nb"))
    }

    @Test
    fun aLineOrParagraphSeparatorIsEscapedAndNeverShownRaw() {
        val shown = printableOrHex("a\u2028b\u2029c")

        assertEquals("a\\u2028b\\u2029c", shown)
        assertTrue(shown.none { it == '\u2028' || it == '\u2029' })
        // A backslash followed by "u2028" is another string, so it shows differently.
        assertNotEquals(shown, printableOrHex("a\\u2028b\\u2029c"))
    }
}
