package app.lensly.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainNameTest {
    @Test
    fun plainNamesAreKept() {
        assertEquals("Alice Müller 🙂", displayNameOf("Alice Müller 🙂"))
    }

    @Test
    fun controlAndBidiCharactersAreStripped() {
        assertEquals("Alice", displayNameOf("‮Alice‬"))
        assertEquals("AliceBob", displayNameOf("Alice\n⁦Bob⁩​"))
        assertEquals("Alice", displayNameOf("  Alice\t\u0000 "))
        assertEquals("Alice", displayNameOf("Al ice "))
    }

    @Test
    fun longNamesAreCut() {
        val shown = displayNameOf("a".repeat(100))
        assertEquals(DISPLAY_NAME_MAX, shown.length)
        assertEquals("a".repeat(DISPLAY_NAME_MAX - 1) + "…", shown)
        assertEquals("a".repeat(DISPLAY_NAME_MAX), displayNameOf("a".repeat(DISPLAY_NAME_MAX)))
    }
}
