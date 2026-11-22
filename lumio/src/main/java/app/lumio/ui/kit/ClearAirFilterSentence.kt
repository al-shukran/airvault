package app.lumio.ui.kit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.portfolio.SentencePart
import app.lumio.portfolio.SentencePicker

/**
 * The filter sentence: the sentence that says what a list shows. Each underlined phrase opens one short radio sheet, and a choice rewrites the sentence
 * in place. It reads like text and wraps like text, so it never becomes a wall of pills.
 */
@Composable
fun ClearAirFilterSentence(parts: List<SentencePart>, onPick: (SentencePicker) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val sentence = buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is SentencePart.Text -> append(part.text)
                is SentencePart.Pick -> withLink(
                    LinkAnnotation.Clickable(
                        tag = part.picker.name,
                        styles = TextLinkStyles(SpanStyle(color = palette.ink, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onPick(part.picker) },
                    ),
                ) { append(part.text) }
            }
        }
    }
    Text(
        text = sentence,
        modifier = modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        style = ClearAirType.body,
        color = palette.ink2,
    )
}
