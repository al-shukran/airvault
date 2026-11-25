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

/** [plainNameOf] of [name], or null when [name] is null or has nothing visible left. */
fun plainNameOrNull(name: String?, max: Int = PLAIN_NAME_MAX): String? = name?.let { plainNameOf(it, max) }?.takeIf { it.isNotEmpty() }
