package app.lensly.ui.gate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface

/**
 * Password display: one dot-matrix dot per entered character. It takes only the character count, so
 * the plaintext never reaches the UI layer. There is deliberately no reveal toggle.
 *
 * When the field is empty it shows a short muted dash. Once the row is full it stays full, and the
 * accessibility description still reports the exact character count.
 */
@Composable
fun PasswordField(
    length: Int,
    modifier: Modifier = Modifier,
) {
    val description = if (length == 0) "Password empty" else "$length characters entered"
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .semantics { contentDescription = description },
    ) {
        val dot = DOT_DIAMETER.toPx()
        val pitch = DOT_PITCH.toPx()
        val centerY = size.height / 2f

        if (length == 0) {
            val dashWidth = DASH_WIDTH.toPx()
            val dashHeight = DASH_HEIGHT.toPx()
            drawRect(
                color = LenslyMuted,
                topLeft = Offset(0f, centerY - dashHeight / 2f),
                size = Size(dashWidth, dashHeight),
            )
            return@Canvas
        }

        // Dots fill from the left. Extra characters past the last slot are not drawn.
        val maxVisible = ((size.width + (pitch - dot)) / pitch).toInt().coerceAtLeast(1)
        val shown = minOf(length, maxVisible)
        val radius = dot / 2f
        for (i in 0 until shown) {
            val x = radius + i * pitch
            drawCircle(color = LenslyOnSurface, radius = radius, center = Offset(x, centerY))
        }
    }
}

private val FIELD_HEIGHT = 32.dp
private val DOT_DIAMETER = 12.dp
private val DOT_PITCH = 18.dp
private val DASH_WIDTH = 16.dp
private val DASH_HEIGHT = 2.dp
