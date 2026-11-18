package app.lumio.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion

/** The height of the thread, which holds its nodes. */
private val THREAD_HEIGHT = 16.dp

/** The radius of a node, and the width of the line between them. */
private val NODE_RADIUS = 5.dp
private val LINE_WIDTH = 2.dp

/**
 * The step thread of a send (section 2.1): [steps] nodes joined by a line, with the nodes up to [current] filled in gold. The gold part of the line grows
 * on a spring to the current node, and reduced motion moves it at once.
 */
@Composable
fun StepThread(current: Int, modifier: Modifier = Modifier, steps: Int = 4) {
    val last = (steps - 1).coerceAtLeast(1)
    val fill = animateFloatAsState(
        targetValue = current.coerceIn(0, last).toFloat() / last,
        animationSpec = if (LocalReducedMotion.current) snap() else LumenMotion.spatial,
        label = "step thread fill",
    )
    val gold = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val unreached = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = modifier.fillMaxWidth().height(THREAD_HEIGHT)) {
        val radius = NODE_RADIUS.toPx()
        val line = LINE_WIDTH.toPx()
        val span = size.width - 2f * radius
        val y = size.height / 2f
        drawLine(color = track, start = Offset(radius, y), end = Offset(radius + span, y), strokeWidth = line, cap = StrokeCap.Round)
        drawLine(color = gold, start = Offset(radius, y), end = Offset(radius + span * fill.value, y), strokeWidth = line, cap = StrokeCap.Round)
        for (step in 0 until steps) {
            val x = radius + span * step / last
            val reached = step <= current
            drawCircle(color = if (reached) gold else unreached, radius = radius, center = Offset(x, y))
            if (!reached) drawCircle(color = track, radius = radius, center = Offset(x, y), style = Stroke(width = line))
        }
    }
}
