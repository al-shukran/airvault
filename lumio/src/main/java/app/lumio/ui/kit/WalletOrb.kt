package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A wallet's orb (section 1.5): a sphere lit from 30 % across and 25 % down, in the hue that its [fingerprint] gives, so each wallet keeps its colour.
 * A 1 dp white rim at 20 % sits inside its edge. It is 40 dp in a list and 72 dp as a hero.
 */
@Composable
fun WalletOrb(fingerprint: Int, modifier: Modifier = Modifier, diameter: Dp = 40.dp) {
    val hue = Math.floorMod(fingerprint, 360).toFloat()
    Box(
        modifier = modifier
            .size(diameter)
            .drawWithCache {
                val light = Brush.radialGradient(
                    colors = listOf(
                        Color.hsl(hue, 0.80f, 0.72f),
                        Color.hsl((hue + 40f) % 360f, 0.70f, 0.46f),
                        Color.hsl((hue + 60f) % 360f, 0.60f, 0.22f),
                    ),
                    center = Offset(size.width * 0.30f, size.height * 0.25f),
                    radius = size.maxDimension,
                )
                val rim = 1.dp.toPx()
                onDrawBehind {
                    drawCircle(brush = light)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.20f),
                        radius = size.minDimension / 2f - rim / 2f,
                        style = Stroke(width = rim),
                    )
                }
            },
    )
}
