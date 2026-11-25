package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a status band, from the proposal. */
private val BAND_HEIGHT = 28.dp

/**
 * A status band under a top bar: [text] in small ink2 on the sunk fill, with no icon and no animation. The offline band uses it while there is no
 * network.
 */
@Composable
fun ClearAirStatusBand(text: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier.fillMaxWidth().height(BAND_HEIGHT).background(palette.raised).padding(horizontal = ClearAirSpacing.gutter),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = ClearAirType.caption.copy(lineHeight = 16.sp),
            color = palette.ink2,
            maxLines = 1,
        )
    }
}
