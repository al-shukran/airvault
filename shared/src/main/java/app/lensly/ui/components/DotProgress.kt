package app.lensly.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyOnSurface

private const val PROGRESS_DOTS = 7
private val PROGRESS_DOT_DIAMETER = 6.dp
private val PROGRESS_PITCH = 12.dp

/**
 * Subtle dot-matrix progress: a row of dots with a soft highlight that moves along it. The highlight wraps around, so it
 * never stalls. [description] is what TalkBack reads for the row.
 */
@Composable
fun DotProgress(
    modifier: Modifier = Modifier,
    description: String = "Unlocking",
) {
    val transition = rememberInfiniteTransition(label = "unlock-progress")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = PROGRESS_DOTS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "unlock-phase",
    )
    val width = PROGRESS_PITCH * (PROGRESS_DOTS - 1) + PROGRESS_DOT_DIAMETER
    Canvas(
        modifier = modifier
            .size(width = width, height = PROGRESS_DOT_DIAMETER)
            .semantics { contentDescription = description },
    ) {
        val radius = PROGRESS_DOT_DIAMETER.toPx() / 2f
        val pitch = PROGRESS_PITCH.toPx()
        val centerY = size.height / 2f
        for (i in 0 until PROGRESS_DOTS) {
            // Distance from the moving highlight, measured around the loop.
            val raw = ((i - phase) % PROGRESS_DOTS + PROGRESS_DOTS) % PROGRESS_DOTS
            val distance = minOf(raw, PROGRESS_DOTS - raw)
            val alpha = (1f - distance / 2.5f).coerceIn(0.15f, 1f)
            drawCircle(
                color = LenslyOnSurface.copy(alpha = alpha),
                radius = radius,
                center = Offset(radius + i * pitch, centerY),
            )
        }
    }
}
