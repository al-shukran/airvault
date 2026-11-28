package app.airvault.core.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The most nonspacing or enclosing marks (Mn, Me) that may follow one another. */
private const val MAX_STACKED_MARKS = 4

/** The message as text, when it is UTF-8 that [isPrintableText] accepts. Null for any other bytes. */
fun printableText(bytes: ByteArray): String? = try {
    val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    text.takeIf { it.isNotEmpty() && isPrintableText(text) }
} catch (e: CharacterCodingException) {
    null
}

/**
 * True for a code point a reader can check by eye. Line breaks and tabs are fine. Controls, invisible format characters such as a
 * right-to-left override or a tag character, private-use and unassigned characters, and an unpaired surrogate are not, so the text shows
 * its bytes. Text is checked code point by code point, so a character outside the Basic Multilingual Plane is classified as one character.
 */
fun isPrintableCodePoint(codePoint: Int): Boolean = when (codePoint) {
    '\n'.code, '\r'.code, '\t'.code -> true
    else -> when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.SURROGATE.toInt(),
        -> false
        else -> true
    }
}

/**
 * True when every code point of [text] is [isPrintableCodePoint] and no more than four nonspacing or enclosing marks (Mn, Me) follow one
 * another. Real scripts stack at most that many (Hebrew with a point, a vowel and a cantillation mark, Arabic with a shadda and its vowels,
 * Vietnamese with two), and a longer stack is a text made to draw outside its row, so it is not printable and the text shows its bytes.
 */
fun isPrintableText(text: String): Boolean {
    var marks = 0
    return text.codePoints().allMatch {
        val type = Character.getType(it)
        marks = if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) marks + 1 else 0
        marks <= MAX_STACKED_MARKS && isPrintableCodePoint(it)
    }
}

/**
 * True when every code point of [text] is one [isPrintableText] accepts and none is a control character (which covers the line break, the
 * tab and the next-line character U+0085) or a line or paragraph separator (U+2028, U+2029), so nothing can start a row. Each code point is
 * checked as one character, so a character outside the Basic Multilingual Plane is not hidden in two units.
 */
fun isSingleLinePrintable(text: String): Boolean = isPrintableText(text) && text.codePoints().allMatch {
    when (Character.getType(it)) {
        Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> false
        else -> true
    }
}
