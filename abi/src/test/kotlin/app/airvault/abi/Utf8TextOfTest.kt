package app.airvault.abi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Utf8TextOfTest {
    /** U+E0041, a tag character: it is invisible and reads as the letter A. Outside the Basic Multilingual Plane, it is two UTF-16 units. */
    private val hiddenTag = String(Character.toChars(0xE0041))

    @Test
    fun aTagCharacterHiddenInTextIsRefused() {
        assertNull(utf8TextOf("Pay$hiddenTag".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun aLineBreakIsRefused() {
        assertNull(utf8TextOf("a\nb".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun printableTextIsKept() {
        assertEquals("Sign in é 😀", utf8TextOf("Sign in é 😀".toByteArray(Charsets.UTF_8)))
    }
}
