package app.lensly.ui.wallet

import org.web3j.crypto.MnemonicUtils

/** The 2048 English BIP39 words, sorted once and shared by the quiz and the restore screen. */
internal object Bip39Wordlist {
    val words: List<String> by lazy { MnemonicUtils.getWords().sorted() }
}

/**
 * Prefix suggestions for the restore screen. The list is sorted once and each lookup is a binary
 * search, so the 2048 words are never scanned per keystroke.
 */
class Bip39Autocomplete(
    wordlist: List<String> = Bip39Wordlist.words,
    private val maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
) {
    private val sorted: List<String> = wordlist.sorted()
    private val known: Set<String> = sorted.toHashSet()

    /** Up to [maxSuggestions] words that start with [prefix], in alphabetical order. Empty prefix gives none. */
    fun suggest(prefix: String): List<String> {
        val typed = prefix.lowercase()
        if (typed.isEmpty()) return emptyList()

        var index = firstAtOrAfter(typed)
        val result = ArrayList<String>(maxSuggestions)
        while (index < sorted.size && result.size < maxSuggestions && sorted[index].startsWith(typed)) {
            result.add(sorted[index])
            index++
        }
        return result
    }

    /** True when [word] is exactly one of the BIP39 words. */
    fun isWord(word: String): Boolean = word in known

    /**
     * True when [prefix] is a BIP39 word and no other BIP39 word starts with it, so typing it settles the word. "act" is a word
     * too, but "action" starts with it, so the owner still chooses. The words that start with a word sit right after it in the
     * sorted list, so the next entry is enough to tell.
     */
    fun isUniqueWord(prefix: String): Boolean {
        val typed = prefix.lowercase()
        val index = firstAtOrAfter(typed)
        return sorted.getOrNull(index) == typed && sorted.getOrNull(index + 1)?.startsWith(typed) != true
    }

    /** The index of the first sorted word that is not before [typed]. */
    private fun firstAtOrAfter(typed: String): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < typed) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val DEFAULT_MAX_SUGGESTIONS = 4
    }
}
