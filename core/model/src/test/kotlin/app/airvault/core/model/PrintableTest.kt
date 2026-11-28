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

    @Test
    fun aTextWithLongStacksOfCombiningMarksIsNotPrintable() {
        val stacked = "o" + "\u0332".repeat(20)
        assertFalse(isSingleLinePrintable(stacked))
        assertFalse(isPrintableText(stacked))
        assertNull(printableText(stacked.toByteArray(Charsets.UTF_8)))
        assertFalse(isSingleLinePrintable(("o" + "\u0332".repeat(20)).repeat(12)))
        assertFalse(isSingleLinePrintable("o" + "\u030D\u0329".repeat(10)))
        // The stack is counted from the first mark on, so a mark of an enclosing kind counts too, and a letter between two stacks resets it.
        assertFalse(isPrintableText("o" + "\u20DD".repeat(5)))
        assertTrue(isPrintableText("o" + "\u0332".repeat(4) + "o" + "\u0332".repeat(4)))
        assertFalse(isPrintableText("o" + "\u0332".repeat(5)))
        assertFalse(isPrintableText("\u0332".repeat(5)))
    }

    @Test
    fun realWritingWithAccentsAndStackedMarksIsStillPrintable() {
        val texts = listOf(
            "caf\u00e9 na\u00efve",
            "e\u0301 a\u0308", // decomposed accents
            "Ti\u1ebfng Vi\u1ec7t", // Vietnamese, precomposed
            "Tie\u0302\u0301ng Vie\u0323\u0302t", // Vietnamese, decomposed: a circumflex and an acute, a dot below and a circumflex
            "\u0928\u092E\u0938\u094D\u0924\u0947", // Devanagari: namaste
            "\u0915\u093C\u094D\u0937", // Devanagari: ka with a nukta, a virama, then ssa
            "\u05E9\u05C1\u05B0\u05BC\u05B8", // Hebrew: shin with a dot, a sheva, a dagesh and a qamats
            "\u0645\u064F\u062D\u064E\u0645\u0651\u064E\u062F", // Arabic with a shadda and vowels
            "\u0e17\u0e35\u0e48", // Thai: a vowel and a tone mark on one consonant
        )
        for (text in texts) {
            assertTrue(isSingleLinePrintable(text), text)
            assertTrue(isPrintableText(text), text)
            assertEquals(text, printableText(text.toByteArray(Charsets.UTF_8)))
        }
    }
}
