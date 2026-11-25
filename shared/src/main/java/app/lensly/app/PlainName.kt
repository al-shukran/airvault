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

/** "0x" and 4 hex digits anywhere after a word break, also after one separator ("0x 71C7", "0x-71C7"). */
private val HEX_START = Regex("""\b0x[^\p{L}\p{N}]?[0-9a-f]{4}""", RegexOption.IGNORE_CASE)

/** A name whose joined text begins with "0x" and 4 hex digits, whatever splits them ("0x71 C7 656E"). */
private val JOINED_HEX_START = Regex("""^0x[0-9a-f]{4}""", RegexOption.IGNORE_CASE)

/** A first piece that begins like an address: "ox" (the look-alike of "0x") and 4 hex digits, or a bech32 prefix and a body. "Ox Cafe" and "BC1 Savings" are names. */
private val FIRST_PIECE_START = Regex("""^(?:ox[0-9a-f]{4}|(?:bc1|tb1)[0-9a-z]{6,})""", RegexOption.IGNORE_CASE)

private val ALPHANUMERIC = Regex("""[\p{L}\p{N}]+""")
private val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

/** Capitalised humps of 3 or more letters, after an optional lowercase letter and an optional 2-letter hump: "MetaMask", "iPhone", "McDonald". */
private val HUMPS = Regex("""\p{Ll}?(?:\p{Lu}\p{Ll})?(?:\p{Lu}\p{Ll}{2,})+""")

/** What may stand between two address-like pieces of a name: one space, one dash or one slash. Anything else reads as an elision or a split address. */
private val PLAIN_GAPS = setOf(" ", "-", "/")

/** The longest piece an upper-case or hump-case ("MetaMaskWallet") piece reaches before it is read as an address. */
private const val MAX_WORD = 12

/** The longest lowercase or Capitalised letters-only piece that is still a word ("Internationalization" has 20, "Counterrevolutionaries" 22); a longer one is read as an address. */
private const val MAX_LONG_WORD = 24

/** A piece this short or shorter can be one group of an address, whatever its look. */
private const val MAX_GROUP = 5

/** A piece this long or longer can be the end of an elided address ("9WzD…Hk3z"). */
private const val MIN_END = 4

/** A joined name or a span of an address-like stretch this long is an address. */
private const val ADDRESS_LENGTH = 20

/** Cyrillic and Greek letters drawn like Latin ones, each with the Latin letter and the same case it is mapped to. */
private val CONFUSABLES: Map<Char, Char> =
    ("аеорсухіѕјһԁԛԝкονκιϲρυ" + "АВСЕНКМОРТХІЅЈΑΒΕΖΗΙΚΜΝΟΡΤΥΧϹ").zip("aeopcyxisjhdqwkovkicpu" + "ABCEHKMOPTXISJABEZHIKMNOPTYXC").toMap()

/** [name] as the address check reads it: NFKC, format characters and combining marks removed, look-alike letters mapped to Latin. */
private fun foldForAddressCheck(name: String): String = buildString {
    Normalizer.normalize(name, Normalizer.Form.NFKC).codePoints().forEach { cp ->
        when (Character.getType(cp).toByte()) {
            Character.FORMAT, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> Unit
            else -> appendCodePoint(if (cp <= 0xFFFF) CONFUSABLES[cp.toChar()]?.code ?: cp else cp)
        }
    }
}

private fun piecesOf(text: String): List<String> = text.split(NOT_ALPHANUMERIC).filter { it.isNotEmpty() }

/**
 * Whether [piece] looks like a part of an address and not like a word: it holds a digit, its letters are in an irregular case ("XwBb",
 * "PKTanMKd"; a word is lowercase, Capitalised, UPPERCASE or made of humps of 3 or more letters such as "MetaMask", "iPhone" or
 * "McDonald"), or it is longer than a word of its case: a lowercase or Capitalised piece ("International", "Opportunities") is a word up to
 * [MAX_LONG_WORD] letters, an upper-case or hump-case piece only up to [MAX_WORD] (an Algorand address is upper-case letters and has no
 * digit about once in 100,000). Letters without case, such as Chinese or Arabic, are neutral.
 */
private fun isAddressLike(piece: String): Boolean {
    if (piece.any(Char::isDigit)) return true
    val letters = piece.filter { it.isUpperCase() || it.isLowerCase() }
    if (letters.isEmpty()) return false
    val lower = letters.all(Char::isLowerCase)
    val upper = letters.all(Char::isUpperCase)
    val capitalised = letters.first().isUpperCase() && letters.drop(1).all(Char::isLowerCase)
    val longest = if (lower || capitalised) MAX_LONG_WORD else MAX_WORD
    return !(lower || upper || capitalised || HUMPS.matches(letters)) || piece.length > longest
}

/** Whether [piece] can be an end of an address for the first-and-last rule: address-like, and not a number or ordinal such as "2024", "401k" or "1st". */
private fun isAddressEnd(piece: String): Boolean =
    isAddressLike(piece) && !(piece.all(Char::isDigit) || (piece.length <= 4 && piece.first().isDigit()))

/**
 * Whether [pieces] hold a stretch of 20 or more characters that reads as an address: in every run of consecutive pieces that are address-like
 * or at most [MAX_GROUP] characters long, the span from the first to the last address-like piece. Words of 6 or more characters end a run, so
 * "Samsung Galaxy S24 Ultra 256GB" is a name, and words before an address do not hide it.
 */
private fun hasAddressSpan(pieces: List<String>): Boolean {
    var i = 0
    while (i < pieces.size) {
        val end = (i until pieces.size).firstOrNull { pieces[it].length > MAX_GROUP && !isAddressLike(pieces[it]) } ?: pieces.size
        val run = pieces.subList(i, end)
        val from = run.indexOfFirst(::isAddressLike)
        if (from >= 0 && run.subList(from, run.indexOfLast(::isAddressLike) + 1).sumOf { it.length } >= ADDRESS_LENGTH) return true
        i = end + 1
    }
    return false
}

/** Whether two neighbouring address-like pieces of [text], each with a letter and [MIN_END] or more characters, have anything but a [PLAIN_GAPS] between them. */
private fun hasElidedAddress(text: String): Boolean = ALPHANUMERIC.findAll(text).zipWithNext().any { (before, after) ->
    listOf(before.value, after.value).all { it.length >= MIN_END && it.any(Char::isLetter) && isAddressLike(it) } &&
        text.substring(before.range.last + 1, after.range.first) !in PLAIN_GAPS
}

/**
 * Whether [name] reads as an address, so that shown in bold above the real one it could be taken for it. People compare the start and the
 * end of an address, so the name is judged by its ends. It is read after folding (NFKC, format characters and combining marks removed,
 * Cyrillic and Greek look-alikes mapped to Latin) and cut into pieces at every character that is not a letter or a digit, so any split by
 * space, slash, colon, dot or dash gives the same pieces (see [isAddressLike]). It is refused when
 * - it starts with "0x" and 4 hex digits (also spaced or split by one separator, or after other words), or its first piece starts with
 *   "ox" and 4 hex digits or with a "bc1" or "tb1" address start;
 * - its first and last pieces both look like address ends ([isAddressEnd]) and it holds 20 or more letters and digits;
 * - two address-like pieces of 4 or more characters are separated by anything but one space, dash or slash ([hasElidedAddress]): "9WzD…Hk3z";
 * - [hasAddressSpan] finds a stretch of 20 or more characters of address-like pieces and short pieces, however many words come before it.
 * So "Samsung Galaxy S24 Ultra 256GB" or "Room 10 x 2020" is a name.
 */
fun looksLikeAddressName(name: String): Boolean {
    val text = foldForAddressCheck(name)
    val pieces = piecesOf(text)
    if (pieces.isEmpty()) return false
    val joined = pieces.joinToString("")
    return HEX_START.containsMatchIn(text) || JOINED_HEX_START.containsMatchIn(joined) || FIRST_PIECE_START.containsMatchIn(pieces.first()) ||
        (joined.length >= ADDRESS_LENGTH && isAddressEnd(pieces.first()) && isAddressEnd(pieces.last())) ||
        hasElidedAddress(text) || hasAddressSpan(pieces)
}

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
