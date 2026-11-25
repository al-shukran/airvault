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

/** "0x" and 4 hex digits anywhere after a word break, also spaced ("0x 71C7"). */
private val HEX_START = Regex("""\b0x\s?[0-9a-f]{4}""", RegexOption.IGNORE_CASE)

/** The joined name begins like an address: "0x" (or its look-alike "ox") and 4 hex digits, or a bech32 prefix and a body. */
private val JOINED_START = Regex("""^(?:[0o]x[0-9a-f]{4}|(?:bc1|tb1)[0-9a-z]{6,})""", RegexOption.IGNORE_CASE)

private val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

/** The longest piece an ordinary word or a Lensly group of 4 reaches: a longer all-lowercase or all-uppercase piece is read as an address. */
private const val MAX_WORD = 12

/** A piece or a joined name this long, with the right look, is an address. */
private const val ADDRESS_LENGTH = 20

/** Cyrillic and Greek letters drawn like Latin ones, each with the Latin letter and the same case it is mapped to. */
private val CONFUSABLES: Map<Char, Char> =
    ("аеорсухіѕјһԁԛԝкονκιϲρυ" + "АВСЕНКМОРТХІЅЈΑΒΕΖΗΙΚΜΝΟΡΤΥΧϹ").zip("aeopcyxisjhdqwkovkicpu" + "ABCEHKMOPTXISJABEZHIKMNOPTYXC").toMap()

/** [name] as the address check reads it: NFKC, format characters and combining marks removed, look-alike letters mapped to Latin. */
private fun foldForAddressCheck(name: String): String = buildString {
    Normalizer.normalize(name, Normalizer.Form.NFKC).forEach { c ->
        when (Character.getType(c).toByte()) {
            Character.FORMAT, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> Unit
            else -> append(CONFUSABLES[c] ?: c)
        }
    }
}

private fun piecesOf(text: String): List<String> = text.split(NOT_ALPHANUMERIC).filter { it.isNotEmpty() }

/**
 * Whether [piece] looks like a part of an address and not like a word: it holds a digit, its letters are in an irregular case ("XwBb",
 * "PKTanMKd"; a word is lowercase, Capitalised, UPPERCASE or camel like "iPhone"), or it is a lowercase or uppercase run longer than
 * [MAX_WORD] (an Algorand address has no digit). Letters without case, such as Chinese or Arabic, are neutral.
 */
private fun isAddressLike(piece: String): Boolean {
    if (piece.any(Char::isDigit)) return true
    val letters = piece.filter { it.isUpperCase() || it.isLowerCase() }
    if (letters.isEmpty()) return false
    val lower = letters.all(Char::isLowerCase)
    val upper = letters.all(Char::isUpperCase)
    val capitalised = letters.first().isUpperCase() && letters.drop(1).all(Char::isLowerCase)
    val camel = letters.length > 2 && letters[0].isLowerCase() && letters[1].isUpperCase() && letters.drop(2).all(Char::isLowerCase)
    return !(lower || upper || capitalised || camel) || (piece.length > MAX_WORD && (lower || upper))
}

/**
 * Whether [name] reads as an address, so that shown in bold above the real one it could be taken for it. People compare the start and the
 * end of an address, so the name is judged by its ends. It is read after folding (NFKC, format characters and combining marks removed,
 * Cyrillic and Greek look-alikes mapped to Latin) and cut into pieces at every character that is not a letter or a digit, so any split by
 * space, slash, colon, dot or dash gives the same pieces (see [isAddressLike]). It is refused when
 * - it starts with "0x" and 4 hex digits (also spaced, or after other words) or with a "bc1" or "tb1" address start, once joined;
 * - its first and last pieces both look like address parts and it holds 20 or more letters and digits;
 * - an ellipsis has an address-like piece on each side ("9WzD…Hk3z");
 * - one piece of 20 or more characters looks like an address part.
 * So "Samsung Galaxy S24 Ultra 256GB" or "Room 10 x 2020" is a name.
 */
fun looksLikeAddressName(name: String): Boolean {
    val text = foldForAddressCheck(name)
    val pieces = piecesOf(text)
    if (pieces.isEmpty()) return false
    val joined = pieces.joinToString("")
    return HEX_START.containsMatchIn(text) || JOINED_START.containsMatchIn(joined) ||
        (joined.length >= ADDRESS_LENGTH && isAddressLike(pieces.first()) && isAddressLike(pieces.last())) ||
        text.split("...").map(::piecesOf).zipWithNext().any { (before, after) ->
            before.isNotEmpty() && after.isNotEmpty() && isAddressLike(before.last()) && isAddressLike(after.first())
        } ||
        pieces.any { it.length >= ADDRESS_LENGTH && isAddressLike(it) }
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
