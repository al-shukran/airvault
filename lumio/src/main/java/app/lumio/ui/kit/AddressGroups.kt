package app.lumio.ui.kit

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlin.math.min

/**
 * An address shown in groups of four after its `0x`, as `groupAddress` writes it: `0x3a98 f1c2 7b40 d6e5`. The first group holds the `0x` and four
 * digits, and a space sits before each later group. The spaces are on screen only. The value keeps the address as it was typed, and the cursor
 * moves over the digits and never stops on a space.
 */
object AddressGroups : VisualTransformation {
    /** The offset where the first space goes: after the `0x` and four digits. */
    private const val FIRST = 6

    /** The digits in each group after the first. */
    private const val GROUP = 4

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = buildString {
            raw.forEachIndexed { index, char ->
                if (index >= FIRST && (index - FIRST) % GROUP == 0) append(' ')
                append(char)
            }
        }
        val spaces = spacesIn(raw.length)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + spacesBeforeOffset(offset, spaces)

            override fun transformedToOriginal(offset: Int): Int =
                (offset - spacesBeforeShown(offset, spaces)).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }

    /** The spaces that a value of [length] characters shows. */
    private fun spacesIn(length: Int): Int = if (length <= FIRST) 0 else (length - FIRST - 1) / GROUP + 1

    /** The spaces shown before the typed character at [offset]. */
    private fun spacesBeforeOffset(offset: Int, spaces: Int): Int = if (offset < FIRST) 0 else min((offset - FIRST) / GROUP + 1, spaces)

    /** The spaces before [offset] in the shown text. The first space is at shown offset 6, and each later one is five places on: a group is four digits and a space. */
    private fun spacesBeforeShown(offset: Int, spaces: Int): Int = if (offset <= FIRST) 0 else min((offset - FIRST + GROUP) / (GROUP + 1), spaces)
}
