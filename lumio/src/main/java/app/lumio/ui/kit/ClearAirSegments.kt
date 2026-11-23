package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * A row of hairline segments, one for each of [fractions]: each is a track, filled from the left by its fraction (0 to 1). It is the progress bar of
 * the first-run chapters and of the Finish Setting Up checklist.
 */
@Composable
fun ClearAirSegments(fractions: List<Float>, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xxs)) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.hairline),
            ) {
                if (fraction > 0f) {
                    Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.ink))
                }
            }
        }
    }
}
