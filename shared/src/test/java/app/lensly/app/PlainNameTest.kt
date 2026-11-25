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
    fun anAddressSplitAnyWayIsRecognised() {
        assertTrue(looksLikeAddressName("9WzD/XwBb/mkg8/ZTbN/MqUx/vQRA/yWQp/Hk3z"))
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTbN:MqUxvQRAyWQpHk3z"))
        assertTrue(looksLikeAddressName("9WzD:XwBb:mkg8:ZTbN:MqUx:vQRA"))
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTbN MqUxvQRAyWQpHk3z"))
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTb NMqUxvQRAy WQpHk3zq"))
        assertTrue(looksLikeAddressName("9WzD, XwBb | mkg8 \u2022 ZTbN MqUx vQRA"))
    }

    @Test
    fun wordsInTheMiddleDoNotHideTheEndsOfAnAddress() {
        assertTrue(looksLikeAddressName("9WzD Abcd Efgh Ijkl Mnop Qrst Uvwx Hk3z"))
        assertTrue(looksLikeAddressName("9WzD abcd 3efg hijk mnop Xy9Z"))
        assertTrue(looksLikeAddressName("International Savings Account 9WzD XwBb mkg8 ZTbN MqUx"))
        assertTrue(looksLikeAddressName("My wallet 9WzD XwBb mkg8 ZTbN MqUx Hk3z"))
        assertTrue(looksLikeAddressName("9WzD \u0430bcd 3efg hijk mnop Xy9Z"))
        assertTrue(looksLikeAddressName("9WzD\u2026Hk3z"))
        assertTrue(looksLikeAddressName("9WzD...Hk3z"))
        assertTrue(looksLikeAddressName("1A1zP1\u2026DivfNa"))
        assertTrue(looksLikeAddressName("0x71\u03F27\u2026976F"))
    }

    @Test
    fun anElidedOrOddlySeparatedAddressIsRecognised() {
        for (gap in listOf("..", " . . . ", "\u22EF", "\u00B7\u00B7\u00B7", "***", "\u2022\u2022\u2022", "\u2025", "\u2192", " - ", "\u2026", "...", "~", ":")) {
            assertTrue("9WzD${gap}Hk3z", looksLikeAddressName("9WzD${gap}Hk3z"))
        }
        assertTrue(looksLikeAddressName("Dad 0x-71C7-976F"))
        assertTrue(looksLikeAddressName("Dad 0x:71C7:976F"))
        assertTrue(looksLikeAddressName("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        // A supplementary combining mark (U+E0100) is removed, not turned into a separator.
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTb\uDB40\uDD00NMqUxvQRAy"))
        // One space, dash or slash is a plain gap.
        assertFalse(looksLikeAddressName("Budget 2024/2025"))
        assertFalse(looksLikeAddressName("Savings 2024 - 2025"))
    }

    @Test
    fun namesWithBrandsOrdinalsAndPrefixesStayNames() {
        assertFalse(looksLikeAddressName("Bob's MacBook Pro M2 16 inch 2023"))
        assertFalse(looksLikeAddressName("MacBook Pro 14 inch M3 2024"))
        assertFalse(looksLikeAddressName("Dad's OnePlus 12 5G 256GB 2024"))
        assertFalse(looksLikeAddressName("MetaMask Wallet on MacBook"))
        assertFalse(looksLikeAddressName("GitHub MetaMask PayPal"))
        assertFalse(looksLikeAddressName("McDonald PancakeSwap WhatsApp"))
        assertFalse(looksLikeAddressName("2024 Savings Account 2025"))
        assertFalse(looksLikeAddressName("401k Retirement Fund 2045"))
        assertFalse(looksLikeAddressName("1st Savings Account 2nd"))
        assertFalse(looksLikeAddressName("S24 Ultra Samsung Galaxy Phone 5G"))
        assertFalse(looksLikeAddressName("BC1 Savings"))
        assertFalse(looksLikeAddressName("TB1 Backup disk"))
        assertFalse(looksLikeAddressName("Ox Cafe"))
        assertFalse(looksLikeAddressName("Ox Dead Fish"))
    }

    @Test
    fun lookalikeAndOtherScriptCharactersInsideAnAddressDoNotHideIt() {
        // Cyrillic Х, В and х for the Latin X, B and x.
        assertTrue(looksLikeAddressName("9WzD\u0425w\u0412bmkg8ZTbNMqU\u0445vQRAy"))
        assertTrue(looksLikeAddressName("9WzD\u0425w\u0412bmkg8 ZTbNMqU\u0445vQRAy"))
        // Greek omicron and nu, Cyrillic er, u, i and ka.
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZTbNMqUxvQRA\u03BFy\u03BD"))
        assertTrue(looksLikeAddressName("9WzDXwBbmkg8ZT\u0440\u0443\u0456\u043AMqUxvQRAy"))
        // Greek omicron and Cyrillic ha for "0x", and Cyrillic capitals.
        assertTrue(looksLikeAddressName("\u03BF\u0445" + "71C7656E"))
        assertTrue(looksLikeAddressName("\u0410\u0412\u0421\u0415\u041D\u041A\u041C\u041E\u0420\u0422\u0425\u0410\u0412\u0421\u0415\u041D\u041A\u041C\u041E\u0420\u0422\u04251234"))
        // A letter run with no digit, in an irregular case or very long, and an Arabic-Indic digit.
        assertTrue(looksLikeAddressName("HkzAbcDefGhiJkmNopQrsTuvWxyzAbcdEfghJkmNpqRs"))
        assertTrue(looksLikeAddressName("A".repeat(57) + "B"))
        assertTrue(looksLikeAddressName("WzDXwBbmkgZTbNMqUxvQRAyWQp\u0663"))
    }

    @Test
    fun wordsWithModelNumbersAndCodesAreNames() {
        assertFalse(looksLikeAddressName("Samsung Galaxy S24 Ultra 256GB"))
        assertFalse(looksLikeAddressName("Savings2024 Fund Account"))
        assertFalse(looksLikeAddressName("iPhone15 Pro Max 2024 Case"))
        assertFalse(looksLikeAddressName("Ledger Nano X 2024 Backup Wallet"))
        assertFalse(looksLikeAddressName("Family/Savings: 2024 Emergency Fund"))
        assertFalse(looksLikeAddressName("MAIN WALLET 2024 BACKUP KEY"))
        assertFalse(looksLikeAddressName("COLD STORAGE USB 2024 SECOND"))
        assertFalse(looksLikeAddressName("\u6211\u7684\u4EE5\u592A\u574A\u94B1\u5305 ETH \u50A8\u84C4\u8D26\u6237\u5907\u7528 2024"))
        assertFalse(looksLikeAddressName("Wallet 1 2 3 4 5 6 7 8 9 10 11 12 13 14 ETH"))
        assertFalse(looksLikeAddressName("Mom iPhone 15 Pro Max 256GB Space Black"))
        assertFalse(looksLikeAddressName("Room 10 x 2020"))
        assertFalse(looksLikeAddressName("Toyota Corolla 2019 VIN JTDKB20U"))
        assertFalse(looksLikeAddressName("SN ABC12345 XYZ67890 LEDGER"))
        assertFalse(looksLikeAddressName("\u041C\u043E\u0439 \u043A\u043E\u0448\u0435\u043B\u0435\u043A 2024"))
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
