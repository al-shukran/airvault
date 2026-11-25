package app.lensly.app

import app.lensly.ui.components.groupAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainNameTest {
    @Test
    fun plainNamesAreKept() {
        assertEquals("Alice Müller 🙂", plainNameOf("Alice Müller 🙂"))
        assertEquals("José", plainNameOf("Jose\u0301"))
    }

    @Test
    fun controlAndBidiCharactersAreStripped() {
        assertEquals("Alice", plainNameOf("\u202EAlice\u202C"))
        assertEquals("AliceBob", plainNameOf("Alice\n\u2066Bob\u2069\u200B"))
        assertEquals("Alice", plainNameOf("  Alice\t\u0000 "))
        assertEquals("Alice", plainNameOf("Al\u200Bice"))
    }

    @Test
    fun combiningMarksAreRemoved() {
        assertEquals("Zalgo", plainNameOf("Z\u0300\u0301\u0302a\u0489l\u0316g\u0317o\u0318"))
    }

    @Test
    fun spacesBecomeOnePlainSpace() {
        assertEquals("Alice Smith", plainNameOf("Alice\u00A0Smith"))
        assertEquals("Alice Smith", plainNameOf("Alice\u3000\u2003 \u00A0Smith"))
        assertEquals("Alice Smith", plainNameOf("Alice \u200B Smith"))
        assertEquals("Alice", plainNameOf("\u00A0\u3000Alice\u00A0"))
    }

    @Test
    fun invisibleFillersAreRemoved() {
        assertEquals("", plainNameOf("\u3164"))
        assertEquals("", plainNameOf("\u115F\u1160\uFFA0\u2800"))
        assertEquals("Alice", plainNameOf("Al\u3164i\u2800ce\u115F"))
        assertNull(plainNameOrNull("\u3164 \u2800"))
        assertNull(plainNameOrNull(null))
        assertEquals("Alice", plainNameOrNull(" Alice "))
    }

    @Test
    fun longNamesAreCut() {
        val shown = plainNameOf("a".repeat(100))
        assertEquals(PLAIN_NAME_MAX, shown.length)
        assertEquals("a".repeat(PLAIN_NAME_MAX - 1) + "…", shown)
        assertEquals("a".repeat(PLAIN_NAME_MAX), plainNameOf("a".repeat(PLAIN_NAME_MAX)))
    }

    @Test
    fun aNameWithNothingVisibleFallsBackToTheGroupedAddress() {
        val entry = AddressBookEntry(id = "1", address = "0x1234567890abcdef1234567890abcdef12345678", name = "\u200B\u3164", label = null)
        assertEquals(groupAddress(entry.address), entry.plainName())
        assertEquals("Alice", entry.copy(name = "\u202EAlice").plainName())
    }
}
