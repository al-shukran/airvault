package app.lensly.app

import java.text.Normalizer

/** The longest name a review shows, in characters. A longer name is cut and ends in an ellipsis. */
const val PLAIN_NAME_MAX = 40

/** Characters that draw nothing but are letters or symbols to Unicode: Hangul and Hangul Jamo fillers and the blank Braille pattern. */
private val INVISIBLE_FILLERS = setOf(0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0)

/** How many combining marks may follow one base character. Arabic harakat and Devanagari matras need two, a stack of ten is an overflow attack. */
private const val MAX_MARKS_PER_BASE = 2

/**
 * [name] as a review shows it: plain text on one line. The name is composed (NFC), then control characters (line breaks and tabs among
 * them), the format characters that reorder or hide text (bidirectional marks and isolates, zero-width characters), the line and
 * paragraph separators and invisible fillers are removed. Combining marks stay, so Arabic and Devanagari names read right, but at most
 * [MAX_MARKS_PER_BASE] follow a base character and a mark with no base (at the start or after a space) is dropped. Every other space
 * (no-break, ideographic) becomes a plain space, runs of spaces collapse to one, the ends are trimmed, and the name is cut to [max]
 * characters, [PLAIN_NAME_MAX] by default. A name with nothing visible left is empty, and a caller shows the address instead. Every name
 * of the Address Book, an account or a label goes through it before a review shows it.
 */
fun plainNameOf(name: String, max: Int = PLAIN_NAME_MAX): String {
    var marks = 0
    var hasBase = false
    val plain = buildString {
        Normalizer.normalize(name, Normalizer.Form.NFC).codePoints().forEach { cp ->
            if (cp in INVISIBLE_FILLERS) return@forEach
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
                -> Unit
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK ->
                    if (hasBase && marks++ < MAX_MARKS_PER_BASE) appendCodePoint(cp)
                Character.SPACE_SEPARATOR -> {
                    hasBase = false
                    if (isNotEmpty() && last() != ' ') append(' ')
                }
                else -> {
                    hasBase = true
                    marks = 0
                    appendCodePoint(cp)
                }
            }
        }
    }.trim()
    return if (plain.codePointCount(0, plain.length) <= max) {
        plain
    } else {
        plain.substring(0, plain.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }
}

/** An address as a name: "0x" and 4 hex digits, a bech32 prefix and a body, or a run of 20 or more letters and digits with a digit and 2 letters. */
private val ADDRESS_START = Regex("""0x\s?[0-9a-f]{4}|\b(?:bc1|tb1)[0-9a-z]{6,}""", RegexOption.IGNORE_CASE)
private val ALPHANUMERIC_RUN = Regex("""[\p{L}\p{N}]{20,}""")

/** Anything that is not a letter or a digit, so a space, slash, colon, dot, dash, comma and a combining mark all cut a name into pieces. */
private val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

/** An ordinary word: one capital at most, then 3 or more lowercase letters, as in "Samsung", "Galaxy" or "Case". Address pieces rarely look like this. */
private val WORD = Regex("""\p{Lu}?\p{Ll}{3,}""")

/** Cyrillic and Greek letters that look like the Latin ones of "0x" and of hex digits, "ох" and "0х" included. Only the "0x" check reads them. */
private val CONFUSABLES = mapOf(
    'о' to '0', 'О' to '0', 'ο' to '0', 'Ο' to '0', 'х' to 'x', 'Х' to 'X', 'Χ' to 'X', 'а' to 'a', 'А' to 'A', 'с' to 'c', 'С' to 'C',
    'е' to 'e', 'Е' to 'E', 'В' to 'B',
)

/** A run of [ALPHANUMERIC_RUN] with a digit, 2 letters and an ASCII letter. Letters of another script inside it do not break it, they make it more suspect. */
private fun hasAddressRun(text: String): Boolean = ALPHANUMERIC_RUN.findAll(text).any { match ->
    val run = match.value
    run.any { it in '0'..'9' } && run.count(Char::isLetter) >= 2 && run.any { it in 'A'..'Z' || it in 'a'..'z' }
}

/**
 * Whether [name] reads as an address, so that shown in bold above the real one it could be taken for it: it holds "0x" and 4 hex digits
 * (also in a spaced form such as "0x71C7 656E … 976F"), a "bc1" or "tb1" address start, or a run of 20 or more letters and digits with a digit and 2
 * letters, which is what a Solana or Algorand address looks like and a word is not. The name is read after NFKC folding (fullwidth "０ｘ").
 * The "0x" start is also read with Cyrillic and Greek lookalikes mapped to Latin. The name is cut into pieces at every character that is not
 * a letter or a digit, so an address split by a space, slash, colon, dot or dash, into any number of pieces of any size, is one run again.
 * Pieces that are plain words ([WORD]) are left out first, so "Samsung Galaxy S24 Ultra 256GB" or "Savings2024 Fund Account" is a name. A
 * letter of another script inside a run that holds Latin letters and a digit counts as part of the run, so a Cyrillic "Х" for an "X" does not hide it.
 */
fun looksLikeAddressName(name: String): Boolean {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFKC)
    val pieces = folded.split(NOT_ALPHANUMERIC).filter { it.isNotEmpty() }
    return hasAddressStart(folded) || hasAddressStart(pieces.joinToString("")) ||
        hasAddressRun(pieces.filterNot { WORD.matches(it) }.joinToString(""))
}

private fun hasAddressStart(text: String): Boolean = ADDRESS_START.containsMatchIn(text.map { CONFUSABLES[it] ?: it }.joinToString(""))

/**
 * [plainNameOf] of [name], or null when [name] is null, has nothing visible left, or [looksLikeAddressName]. A caller shows the grouped
 * address in place of a null. A record saved before the name check keeps its stored name and is shown this way.
 */
fun plainNameOrNull(name: String?, max: Int = PLAIN_NAME_MAX): String? =
    name?.let { plainNameOf(it, max) }?.takeIf { it.isNotEmpty() && !looksLikeAddressName(it) }

/**
 * [name] set apart from the text around it, for a sentence such as "Send 1 ETH to <name> (0x1234 5678 …)". First-strong isolate and pop
 * directional isolate make an Arabic or Hebrew name read in its own direction without reordering the address or the words beside it. [name]
 * has gone through [plainNameOf], which removes every direction character, so these two are the only ones in the sentence.
 */
fun isolated(name: String): String = "\u2068$name\u2069"
