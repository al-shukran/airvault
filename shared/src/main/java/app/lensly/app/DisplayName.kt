package app.lensly.app

/** The longest name a review shows, in characters. A longer name is cut and ends in an ellipsis. */
const val DISPLAY_NAME_MAX = 40

/**
 * [name] as a review shows it: plain text. Control characters (line breaks and tabs among them), the format characters that reorder or hide
 * text (bidirectional marks and isolates, zero-width characters) and the line and paragraph separators are removed, the ends are
 * trimmed, and the name is cut to [DISPLAY_NAME_MAX] characters. Every name of the Address Book, an account or a label goes through it
 * before a review shows it.
 */
fun displayNameOf(name: String): String {
    val plain = buildString {
        name.codePoints().forEach { cp ->
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
                -> Unit
                else -> appendCodePoint(cp)
            }
        }
    }.trim()
    return if (plain.codePointCount(0, plain.length) <= DISPLAY_NAME_MAX) {
        plain
    } else {
        plain.substring(0, plain.offsetByCodePoints(0, DISPLAY_NAME_MAX - 1)).trimEnd() + "…"
    }
}
