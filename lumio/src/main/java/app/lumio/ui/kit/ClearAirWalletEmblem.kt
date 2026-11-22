package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette

/** The side of the emblem's artwork grid, in units. The slab and its spark are drawn on it as the brand has them. */
private const val EMBLEM_GRID = 36f

/** The emblem's three faces and its spark, as the brand draws a wallet: the top face lightest, the right face darkest. */
private val EMBLEM_TOP = Color(0xFF8DBBFF)
private val EMBLEM_LEFT = Color(0xFF3F7BE0)
private val EMBLEM_RIGHT = Color(0xFF2A56A8)
private val EMBLEM_SPARK = Color(0xFFF2A14A)

/** The slab's share of its tile: the slab is 28 units of a 36 unit tile. */
private const val SLAB_SHARE = 28f / 36f

/**
 * A wallet's emblem: an isometric slab with the spark on its top face, on a raised tile of [tile]. The slab fills 28 of the tile's 36 units, so it
 * keeps its proportion at any size. A wallet has one emblem, and it is never a picture of the owner's data.
 */
@Composable
fun ClearAirWalletEmblem(modifier: Modifier = Modifier, tile: Dp = 36.dp) {
    val raised = LocalClearAirPalette.current.raised
    Box(
        modifier = modifier.size(tile).clip(RoundedCornerShape(tile * 0.33f)).background(raised),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(tile * SLAB_SHARE)) {
            val unit = size.width / EMBLEM_GRID
            fun face(color: Color, vararg points: Pair<Float, Float>) {
                val path = Path().apply {
                    moveTo(points[0].first * unit, points[0].second * unit)
                    points.drop(1).forEach { (x, y) -> lineTo(x * unit, y * unit) }
                    close()
                }
                drawPath(path = path, color = color)
            }
            face(EMBLEM_TOP, 18f to 6.5f, 29.2f to 12.9f, 18f to 19.3f, 6.8f to 12.9f)
            face(EMBLEM_LEFT, 6.8f to 12.9f, 18f to 19.3f, 18f to 29.5f, 6.8f to 23.1f)
            face(EMBLEM_RIGHT, 29.2f to 12.9f, 18f to 19.3f, 18f to 29.5f, 29.2f to 23.1f)
            face(EMBLEM_SPARK, 18f to 16.3f, 19.7f to 18.9f, 18f to 21.5f, 16.3f to 18.9f)
        }
    }
}
