package app.airvault.core.model

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The message as text, when it is UTF-8 whose every character is [isPrintable]. Null for any other bytes. */
fun printableText(bytes: ByteArray): String? = try {
    val text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    text.takeIf { it.isNotEmpty() && it.all(::isPrintable) }
} catch (e: CharacterCodingException) {
    null
}

/**
 * True for a character a reader can check by eye. Line breaks and tabs are fine. Controls, invisible format characters
 * such as a right-to-left override, private-use and unassigned characters are not, so the message shows its bytes.
 */
fun isPrintable(char: Char): Boolean = when (char) {
    '\n', '\r', '\t' -> true
    else -> when (Character.getType(char)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> false
        else -> true
    }
}
