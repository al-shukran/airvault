package app.lensly.core.registry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayTextTest {
    /** U+E0041, a tag character: it is invisible and reads as the letter A. Outside the Basic Multilingual Plane, it is two UTF-16 units. */
    private val hiddenTag = String(Character.toChars(0xE0041))

    @Test
    fun aTagCharacterHiddenInTextIsRefused() {
        assertFalse(isDisplayText("Pay$hiddenTag", LABEL_MAX))
    }

    @Test
    fun aLineBreakOrAControlIsRefusedAndPlainTextIsKept() {
        assertFalse(isDisplayText("a\nb", NOTE_MAX))
        assertFalse(isDisplayText("a\u0007b", NOTE_MAX))
        assertTrue(isDisplayText("Sign in", LABEL_MAX))
    }
}
