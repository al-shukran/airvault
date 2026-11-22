package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * A monogram: the [text] of an account's two letters, in a raised disc of [tile]. It names an account in a list without a picture of it, and the
 * letters are the account's own, so the disc never carries a logo.
 */
@Composable
fun ClearAirMonogram(text: String, modifier: Modifier = Modifier, tile: Dp = 36.dp) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier.size(tile).clip(CircleShape).background(palette.raised),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ClearAirType.rowTitle.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
            color = palette.ink,
            maxLines = 1,
        )
    }
}
