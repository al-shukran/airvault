package app.lumio.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion

/** One crossing of the sheen, in milliseconds. */
private const val SHEEN_MS = 1600

/** How wide the sheen band is, as a share of the bar. */
private const val SHEEN_SHARE = 0.5f

/**
 * A bar for a value that has never been read: the raised fill, with a sheen that crosses it every 1.6 s. A bar is drawn only where Lumio has never
 * had the value, and under reduced motion it stays still.
 */
@Composable
fun ClearAirSkeleton(modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val shape = RoundedCornerShape(6.dp)
    if (LocalReducedMotion.current) {
        Box(modifier = modifier.clip(shape).background(palette.raised))
        return
    }
    val transition = rememberInfiniteTransition(label = "clear air skeleton")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHEEN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "clear air skeleton sheen",
    )
    val sheen = palette.ink.copy(alpha = 0.06f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.raised)
            .drawBehind {
                // The band's centre runs from one edge to the other, and the gradient clamps to transparent beyond its ends.
                val band = size.width * SHEEN_SHARE
                val centre = (sweep * 2f - 1f) * size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, sheen, Color.Transparent),
                        startX = centre - band / 2f,
                        endX = centre + band / 2f,
                    ),
                )
            },
    )
}
