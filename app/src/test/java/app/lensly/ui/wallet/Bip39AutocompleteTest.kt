package app.lensly.ui.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class Bip39AutocompleteTest {

    private val autocomplete = Bip39Autocomplete()

    @Test
    fun wordlistHasTheFull2048DistinctEnglishWords() {
        assertEquals(2048, Bip39Wordlist.words.size)
        assertEquals(2048, Bip39Wordlist.words.toSet().size)
    }

    @Test
    fun emptyPrefixSuggestsNothing() {
        assertEquals(emptyList(), autocomplete.suggest(""))
    }

    @Test
    fun suggestsTheFirstFourAlphabeticalMatches() {
        assertEquals(listOf("abandon", "ability", "able", "about"), autocomplete.suggest("ab"))
    }

    @Test
    fun matchingIgnoresCase() {
        assertEquals(autocomplete.suggest("ab"), autocomplete.suggest("AB"))
    }

    @Test
    fun unknownPrefixSuggestsNothing() {
        assertEquals(emptyList(), autocomplete.suggest("zzzq"))
    }

    @Test
    fun exactWordSuggestsItself() {
        assertEquals(listOf("about"), autocomplete.suggest("about"))
    }

    @Test
    fun agreesWithALinearScanForSeveralPrefixes() {
        val all = Bip39Wordlist.words
        for (prefix in listOf("a", "b", "ch", "st", "tr", "wi", "z")) {
            assertEquals(all.filter { it.startsWith(prefix) }.take(4), autocomplete.suggest(prefix), prefix)
        }
    }

    @Test
    fun everySuggestionStartsWithThePrefixAndTheListIsCapped() {
        val sample = Bip39Wordlist.words.take(150) + Bip39Wordlist.words.takeLast(150)
        for (word in sample) {
            for (length in 1..word.length) {
                val prefix = word.take(length)
                val result = autocomplete.suggest(prefix)
                assertTrue(result.size <= Bip39Autocomplete.DEFAULT_MAX_SUGGESTIONS)
                assertTrue(result.all { it.startsWith(prefix) })
                // Fewer than the cap means every match is shown, so the word itself must be there.
                assertTrue(result.size == Bip39Autocomplete.DEFAULT_MAX_SUGGESTIONS || word in result)
            }
        }
    }

    @Test
    fun worksWithAnUnsortedWordlist() {
        val small = Bip39Autocomplete(listOf("banana", "apricot", "apple", "cherry", "apex"))
        assertEquals(listOf("apex", "apple", "apricot"), small.suggest("ap"))
    }

    @Test
    fun capsSuggestionsAtTheConfiguredMaximum() {
        val small = Bip39Autocomplete(listOf("aa", "ab", "ac", "ad", "ae"), maxSuggestions = 2)
        assertEquals(listOf("aa", "ab"), small.suggest("a"))
    }

    @Test
    fun isWordAcceptsOnlyWholeBip39Words() {
        assertTrue(autocomplete.isWord("abandon"))
        assertFalse(autocomplete.isWord("aband"))
        assertFalse(autocomplete.isWord("abandonx"))
    }
}
