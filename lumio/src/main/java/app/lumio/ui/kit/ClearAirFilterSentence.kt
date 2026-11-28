package app.lumio.ui.kit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.portfolio.SentencePart
import app.lumio.portfolio.SentencePicker

/** The sentence's line, as the mock sets it: 14 sp on 24 dp lines. */
private val SENTENCE = ClearAirType.body.copy(fontSize = 14.sp, lineHeight = 24.sp)

/** A phrase's dotted underline: 2 dp dots 2 dp apart, 2 dp thick, its middle 4 dp under the baseline. */
private val DOT = 2.dp
private val UNDERLINE_DROP = 4.dp

/**
 * The filter sentence: the sentence that says what a list shows. Each phrase in semibold ink with a dotted underline opens one short radio sheet, and a
 * choice rewrites the sentence in place. It reads like text and wraps like text, so it never becomes a wall of pills.
 */
@Composable
fun ClearAirFilterSentence(parts: List<SentencePart>, onPick: (SentencePicker) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val phrases = mutableListOf<IntRange>()
    val sentence = buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is SentencePart.Text -> append(part.text)
                is SentencePart.Pick -> {
                    val start = length
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = part.picker.name,
                            styles = TextLinkStyles(SpanStyle(color = palette.ink, fontWeight = FontWeight.SemiBold)),
                            linkInteractionListener = { onPick(part.picker) },
                        ),
                    ) { append(part.text) }
                    phrases += start until length
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = sentence,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ClearAirSpacing.gutter)
            .drawBehind {
                val text = layout ?: return@drawBehind
                val dots = PathEffect.dashPathEffect(floatArrayOf(DOT.toPx(), DOT.toPx()))
                for (phrase in phrases) underlines(text, phrase).forEach { (line, from, to) ->
                    val y = text.getLineBaseline(line) + UNDERLINE_DROP.toPx()
                    drawLine(palette.ink, Offset(from, y), Offset(to, y), strokeWidth = DOT.toPx(), pathEffect = dots)
                }
            },
        style = SENTENCE.inLineBox(),
        color = palette.ink2,
        onTextLayout = { layout = it },
    )
}

/** The stretch of each line that [phrase] covers in [text]: the line, and where the phrase starts and ends on it. */
private fun underlines(text: TextLayoutResult, phrase: IntRange): List<Triple<Int, Float, Float>> {
    val first = text.getLineForOffset(phrase.first)
    val last = text.getLineForOffset(phrase.last)
    return (first..last).map { line ->
        val from = if (line == first) text.getHorizontalPosition(phrase.first, usePrimaryDirection = true) else text.getLineLeft(line)
        val to = if (line == last) text.getHorizontalPosition(phrase.last + 1, usePrimaryDirection = true) else text.getLineRight(line)
        Triple(line, from, to)
    }
}
