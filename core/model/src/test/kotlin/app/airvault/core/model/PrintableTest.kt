package app.airvault.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintableTest {
    @Test
    fun aCharacterAReaderCanSeeAndALineBreakAreFine() {
        assertTrue(isPrintableCodePoint('a'.code))
        assertTrue(isPrintableCodePoint(0x1F600)) // grinning face, a supplementary character a reader can see
        assertTrue(isPrintableCodePoint('\n'.code))
        assertTrue(isPrintableCodePoint('\r'.code))
        assertTrue(isPrintableCodePoint('\t'.code))
    }

    @Test
    fun controlFormatPrivateUseUnassignedAndUnpairedSurrogateCodePointsAreNot() {
        assertFalse(isPrintableCodePoint(0x07)) // bell, a control
        assertFalse(isPrintableCodePoint(0x202E)) // right-to-left override, a format character
        assertFalse(isPrintableCodePoint(0xE0001)) // language tag, a format character
        assertFalse(isPrintableCodePoint(0xE0041)) // tag latin capital letter a, a format character
        assertFalse(isPrintableCodePoint(0xE0002)) // unassigned
        assertFalse(isPrintableCodePoint(0xF0000)) // private use
        assertFalse(isPrintableCodePoint(0xD800)) // an unpaired high surrogate
    }

    @Test
    fun printableTextRefusesAnInvisibleSupplementaryCharacterThatTheCharacterCheckMissed() {
        val emoji = String(Character.toChars(0x1F600))
        val tag = String(Character.toChars(0xE0041))
        assertEquals("Sign in$emoji", printableText("Sign in$emoji".toByteArray(Charsets.UTF_8)))
        assertNull(printableText("Sign in$tag".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun aSingleLineTextRefusesEveryCharacterThatCanStartARow() {
        assertTrue(isSingleLinePrintable("app.example"))
        for (breaker in listOf("\n", "\r", "\t", "\u0085", "\u2028", "\u2029")) {
            assertFalse(isSingleLinePrintable("a${breaker}b"), "U+" + breaker.codePointAt(0).toString(16))
        }
    }
}
