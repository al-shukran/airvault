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
    fun arabicAndHindiNamesKeepTheirMarks() {
        val arabic = "\u0645\u064F\u062D\u064E\u0645\u064E\u0651\u062F"
        val hindi = "\u0928\u092E\u0938\u094D\u0924\u0947 \u0915\u093F\u0930\u0923"
        assertEquals(arabic, plainNameOf(arabic))
        assertEquals(hindi, plainNameOf(hindi))
    }

    @Test
    fun stackedMarksAreCappedAtTwoPerBase() {
        val zalgo = "Z" + "\u0300\u0301\u0302\u0303\u0304\u0305\u0306\u0307\u0308\u0309" + "algo\u0489\u0316\u0317\u0318"
        assertEquals("Z\u0300\u0301algo\u0489\u0316", plainNameOf(zalgo))
    }

    @Test
    fun marksWithNoBaseAreDropped() {
        assertEquals("Alice Bob", plainNameOf("\u0301\u0302Alice \u0301Bob"))
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
