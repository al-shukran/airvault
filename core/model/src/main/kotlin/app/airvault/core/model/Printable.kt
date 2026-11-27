package app.airvault.core.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The message as text, when it is UTF-8 whose every code point is [isPrintableCodePoint]. Null for any other bytes. */
fun printableText(bytes: ByteArray): String? = try {
    val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    text.takeIf { it.isNotEmpty() && text.codePoints().allMatch(::isPrintableCodePoint) }
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
 * True when every code point of [text] is one [isPrintableCodePoint] accepts and none is a control character (which covers the line break, the
 * tab and the next-line character U+0085) or a line or paragraph separator (U+2028, U+2029), so nothing can start a row. Each code point is
 * checked as one character, so a character outside the Basic Multilingual Plane is not hidden in two units.
 */
fun isSingleLinePrintable(text: String): Boolean = text.codePoints().allMatch {
    isPrintableCodePoint(it) && when (Character.getType(it)) {
        Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> false
        else -> true
    }
}
