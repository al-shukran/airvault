package app.lumio.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.lensly.ui.theme.LocalReducedMotion

/** One crossing of the gold sweep, in milliseconds (section 3.4). */
private const val SWEEP_MS = 1200

/**
 * A loading bar for a value that has not arrived: surfaceContainerHigh, with a gold sweep at 8 % that crosses it every 1.2 s. The sweep runs only while
 * the bar is on screen, and reduced motion keeps the bar still.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = MaterialTheme.shapes.extraSmall
    if (LocalReducedMotion.current) {
        Box(modifier = modifier.clip(shape).background(base))
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val sweep by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "skeleton sweep",
        )
        val gold = MaterialTheme.colorScheme.primary
        Box(
            modifier = modifier
                .clip(shape)
                .background(base)
                .drawBehind {
                    // The band's centre runs from one edge to the other, and the gradient clamps to transparent beyond its ends.
                    val centre = (sweep * 2f - 1f) * size.width
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, gold.copy(alpha = 0.08f), Color.Transparent),
                            startX = centre - size.width / 2f,
                            endX = centre + size.width / 2f,
                        ),
                    )
                },
        )
    }
}
