package app.lumio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette

/** A chain's dot: 15 dp, its logo at the 20 of 24 units that the logo art fills, and the ring of the page's own colour that sets it off the dot before it. */
private val DOT = 15.dp
private val LOGO = 12.5.dp
private val RING = 1.5.dp

/** How far each dot overlaps the one before it. */
private val OVERLAP = 4.dp

/**
 * The logo of each chain of a row, small and overlapping, in the order [icons] gives them. It follows the row's description on its line. Each dot has a
 * ring in the page's colour, so a dot reads as set on top of the one before it.
 */
@Composable
fun ChainDots(icons: List<AssetIconSpec>) {
    val page = LocalClearAirPalette.current.background
    Row(horizontalArrangement = Arrangement.spacedBy(-OVERLAP)) {
        icons.forEach { icon ->
            Box(
                modifier = Modifier
                    .size(DOT)
                    .drawBehind { drawCircle(color = page, radius = size.minDimension / 2 + RING.toPx()) }
                    .clip(CircleShape)
                    .background(page),
                contentAlignment = Alignment.Center,
            ) {
                AssetIcon(spec = icon, size = LOGO)
            }
        }
    }
}
