package app.lensly.core.wallet

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class LedgerPassphraseTest {
    private fun issue(p: String): LedgerPassphraseIssue? = ledgerPassphraseIssue(p.toCharArray())

    @Test
    fun empty_and_plain_ascii_are_typable() {
        assertNull(issue(""))
        assertNull(issue("TREZOR"))
        assertNull(issue("correct horse battery staple"))
    }

    @Test
    fun plain_ascii_symbols_are_typable() {
        assertNull(issue("!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"))
        assertNull(issue("Abc 123 !@#"))
    }

    @Test
    fun exactly_100_characters_is_typable() {
        assertNull(issue("a".repeat(100)))
    }

    @Test
    fun one_hundred_and_one_characters_is_too_long() {
        assertEquals(LedgerPassphraseIssue.TOO_LONG, issue("a".repeat(101)))
    }

    @Test
    fun precomposed_e_with_acute_is_untypable() {
        assertEquals(LedgerPassphraseIssue.UNTYPABLE, issue("café"))
    }

    @Test
    fun decomposed_e_with_acute_is_untypable() {
        // "e" followed by U+0301 combining acute: the second character is outside printable ASCII.
        assertEquals(LedgerPassphraseIssue.UNTYPABLE, issue("café"))
    }

    @Test
    fun emoji_is_untypable() {
        assertEquals(LedgerPassphraseIssue.UNTYPABLE, issue("pass😀word"))
    }

    @Test
    fun tab_is_untypable() {
        assertEquals(LedgerPassphraseIssue.UNTYPABLE, issue("a\tb"))
    }

    @Test
    fun fullwidth_letter_is_untypable_without_normalisation() {
        // Fullwidth "A" (U+FF21) is not normalised. It is outside printable ASCII, so it is reported.
        assertEquals(LedgerPassphraseIssue.UNTYPABLE, issue("Ａbc"))
    }

    @Test
    fun untypable_is_reported_before_too_long() {
        assertEquals(LedgerPassphraseIssue.UNTYPABLE, issue("é" + "a".repeat(100)))
    }
}
