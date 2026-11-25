package app.lensly.app

import app.lensly.ui.components.groupAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun anAddressLikeNameIsRecognised() {
        assertTrue(looksLikeAddressName("0x71C7 656E \u2026 976F"))
        assertTrue(looksLikeAddressName("0x71C7656EC7ab88b098defB751B7401B5f6d8976F"))
        assertTrue(looksLikeAddressName("Alice 0X71c7"))
        assertTrue(looksLikeAddressName("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertTrue(looksLikeAddressName("TB1Q0000000"))
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"))
        assertTrue(looksLikeAddressName("VCMJKWOY5P5P7SKMZFFOCEROPJCZOTIJMNIYNUCKH7LRO45JMJP6UYBIJA"))
        assertFalse(looksLikeAddressName("Alice"))
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGY"))
        assertFalse(looksLikeAddressName("Grandmothers Savings"))
        assertFalse(looksLikeAddressName("Savings2024Fund"))
        assertFalse(looksLikeAddressName("Binance2024Main"))
        assertFalse(looksLikeAddressName("MyWallet2024"))
        assertFalse(looksLikeAddressName("Hardware Wallet 2024"))
        assertFalse(looksLikeAddressName("Box 0x1F"))
        assertFalse(looksLikeAddressName("\u0645\u064F\u062D\u064E\u0645\u064E\u0651\u062F"))
    }

    @Test
    fun anAddressLikeNameIsRecognisedWhenGroupedSplitOrDisguised() {
        // Grouped by 4 the way Lensly shows an address, and split by "-", "." or "_".
        assertTrue(looksLikeAddressName("9WzD XpeB QKTk gTrj PKTa nMKd"))
        assertTrue(looksLikeAddressName("71C7 656E C7ab 88b3 20be"))
        assertTrue(looksLikeAddressName("9WzDXpeB-QKTkgTrj-PKTanMKd"))
        assertTrue(looksLikeAddressName("9WzDXpeB.QKTkgTrj.PKTanMKd"))
        assertTrue(looksLikeAddressName("9WzDXpeB_QKTkgTrj_PKTanMKd"))
        // "0x" split from its digits, spaced after the prefix, fullwidth, and with a Cyrillic "х" or "ох".
        assertTrue(looksLikeAddressName("0x-71C7656E"))
        assertTrue(looksLikeAddressName("0x.71C7"))
        assertTrue(looksLikeAddressName("0x71 C7 656E"))
        assertTrue(looksLikeAddressName("\uFF10\uFF58\uFF17\uFF11\uFF23\uFF17\uFF16\uFF15\uFF16\uFF25"))
        assertTrue(looksLikeAddressName("0\u044571C7656E"))
        assertTrue(looksLikeAddressName("\u043E\u044571C7656E"))
        // Words with a year stay names, spaced or not.
        assertFalse(looksLikeAddressName("Rent 2024 March"))
        assertFalse(looksLikeAddressName("My Savings Account 2024 Fund"))
        assertFalse(looksLikeAddressName("\u041E\u0442\u043F\u0443\u0441\u043A 2024"))
    }

    @Test
    fun anAddressLikeNameShowsAsTheGroupedAddress() {
        val entry = AddressBookEntry(id = "1", address = "0x1234567890abcdef1234567890abcdef12345678", name = "0x71C7 656E \u2026 976F", label = null)
        assertNull(plainNameOrNull(entry.name))
        assertEquals(groupAddress(entry.address), entry.plainName())
        // The notes are not a name: they may hold an address, and plainNameOf keeps them.
        assertEquals("pays 0x71C7656E", plainNameOf("pays 0x71C7656E", 200))
    }

    @Test
    fun anIsolatedNameIsBetweenTheFirstStrongIsolateAndItsPop() {
        assertEquals("\u2068Alice\u2069", isolated("Alice"))
        // The sanitizer removes every direction character of a name, so the isolates around it are the only ones in a sentence.
        val hebrew = "\u05D3\u05D5\u05D3"
        val sentence = "Send 1 ETH to ${isolated(plainNameOf("\u202E$hebrew\u2067"))} (0x1234 5678 \u2026)"
        assertEquals("Send 1 ETH to \u2068$hebrew\u2069 (0x1234 5678 \u2026)", sentence)
        assertEquals(2, sentence.count { it in '\u2066'..'\u2069' })
    }
}
