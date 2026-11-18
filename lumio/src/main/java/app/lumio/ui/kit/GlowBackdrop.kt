package app.lumio.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import app.lumio.ui.theme.LocalLumioPalette
import app.lumio.ui.theme.LocalReducedMotion

/** One breath of the glow, in milliseconds: it scales from 0.96 out to 1.04 and back (section 3.4). */
private const val BREATH_MS = 4000

/**
 * The soft light behind a hero (section 1.1): a radial glow in glowCore, fading through glowEdge to nothing, centred high on [content]. It is still,
 * unless [breathing] (a sync in progress), when it scales between 0.96 and 1.04. Reduced motion keeps it still.
 */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier, breathing: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalLumioPalette.current
    Box(
        modifier = modifier
            .drawWithCache {
                val glow = Brush.radialGradient(
                    colors = listOf(palette.glowCore, palette.glowEdge, Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.35f),
                    radius = size.minDimension * 0.8f,
                )
                onDrawBehind { drawRect(brush = glow) }
            }
            .breathe(enabled = breathing && !LocalReducedMotion.current),
        content = content,
    )
}

/** Scales the layer between 0.96 and 1.04 while [enabled]. The infinite animation runs only in that case. */
@Composable
private fun Modifier.breathe(enabled: Boolean): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "glow breath")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
        label = "glow scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        // The glow and the orb in it are rasterised once into an offscreen layer. Each frame then moves that layer, not the gradient.
        compositingStrategy = CompositingStrategy.Offscreen
    }
}
