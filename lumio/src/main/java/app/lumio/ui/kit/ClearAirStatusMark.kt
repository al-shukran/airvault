package app.lumio.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The three states of a status. Each has a colour, a glyph and words, so colour is never the only signal. */
enum class ClearAirTone {
    /** Gains, confirmed and done: vault blue. */
    Settled,

    /** In transit, or waiting for you: the spark, as text. */
    Pending,

    /** Failed, or a loss: rose. */
    Failed,
}

/**
 * A status in words: a [glyph] (one of ▲ ▼ ✓ ◆ !) and the [text], set in the colour of its [tone].
 */
@Composable
fun ClearAirStatusMark(glyph: String, text: String, tone: ClearAirTone, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val color = when (tone) {
        ClearAirTone.Settled -> palette.vaultBlue
        ClearAirTone.Pending -> palette.sparkText
        ClearAirTone.Failed -> palette.rose
    }
    val line = "$glyph $text"
    Text(
        text = line,
        modifier = modifier.spokenMint(line),
        style = ClearAirType.label,
        color = color,
        maxLines = 1,
    )
}
