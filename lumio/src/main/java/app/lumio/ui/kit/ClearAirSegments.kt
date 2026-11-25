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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * A row of hairline segments, one for each of [fractions]: each is a track, filled from the left by its fraction (0 to 1). It is the progress bar of
 * the first-run chapters and of the Finish Setting Up checklist. A segment is [thickness] tall, and [gap] apart from the next.
 */
@Composable
fun ClearAirSegments(fractions: List<Float>, modifier: Modifier = Modifier, thickness: Dp = 4.dp, gap: Dp = ClearAirSpacing.xxs) {
    val palette = LocalClearAirPalette.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        fractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(thickness)
                    .clip(RoundedCornerShape(thickness / 2))
                    .background(palette.hairline),
            ) {
                if (fraction > 0f) {
                    Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.ink))
                }
            }
        }
    }
}
