package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of the testnet band. */
private val BAND_HEIGHT = 32.dp

/** The distance between two hatch lines, measured along the band. */
private val HATCH_STEP = 8.dp

/**
 * The testnet band: diagonal hatch lines across the full width, with [text] set over them. The hatch is a texture, not another colour, so the band
 * marks a testnet without a colour signal.
 */
@Composable
fun ClearAirTestnetHatch(text: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAND_HEIGHT)
            .clipToBounds()
            .drawBehind { drawHatch(palette.testHatch) },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = ClearAirType.label, color = palette.ink, maxLines = 1)
    }
}

/** Lines at 45 degrees, from the bottom left to the top right, one every [HATCH_STEP]. The band clips the ends that run past its edges. */
private fun DrawScope.drawHatch(color: Color) {
    val step = HATCH_STEP.toPx()
    val stroke = 1.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color = color, start = Offset(x, size.height), end = Offset(x + size.height, 0f), strokeWidth = stroke)
        x += step
    }
}
