package app.lensly.app

import java.text.Normalizer

/** The longest name a review shows, in characters. A longer name is cut and ends in an ellipsis. */
const val PLAIN_NAME_MAX = 40

/** Characters that draw nothing but are letters or symbols to Unicode: Hangul and Hangul Jamo fillers and the blank Braille pattern. */
private val INVISIBLE_FILLERS = setOf(0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0)

/**
 * [name] as a review shows it: plain text on one line. The name is composed (NFC), then control characters (line breaks and tabs among
 * them), the format characters that reorder or hide text (bidirectional marks and isolates, zero-width characters), the line and
 * paragraph separators, combining marks and invisible fillers are removed. Every other space (no-break, ideographic) becomes a plain space,
 * runs of spaces collapse to one, the ends are trimmed, and the name is cut to [max] characters, [PLAIN_NAME_MAX] by default. A name
 * with nothing visible left is empty, and a caller shows the address instead. Every name of the Address Book, an account or a label goes
 * through it before a review shows it.
 */
fun plainNameOf(name: String, max: Int = PLAIN_NAME_MAX): String {
    val plain = buildString {
        Normalizer.normalize(name, Normalizer.Form.NFC).codePoints().forEach { cp ->
            when {
                cp in INVISIBLE_FILLERS -> Unit
                else -> when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
                    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK,
                    -> Unit
                    Character.SPACE_SEPARATOR -> if (isNotEmpty() && last() != ' ') append(' ')
                    else -> appendCodePoint(cp)
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
