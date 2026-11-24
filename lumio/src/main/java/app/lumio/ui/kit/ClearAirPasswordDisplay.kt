package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * A password field of Clear Air: its "Password" label above a frame of radius 16. The frame holds one 9 dp dot per character, and a spark caret
 * where the next one goes while [focused]. Empty, it shows [placeholder] in secondary ink. The frame is 2 dp ink while the keyboard is up, and
 * 1.5 dp ink3 when it is not, as [ClearAirField] draws its own. [modifier] moves the frame, for a reject. It takes only the count, so the
 * password never reaches the UI. A tap calls [onClick], which brings the keyboard back.
 */
@Composable
fun ClearAirPasswordDisplay(
    length: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    placeholder: String = "",
) {
    val palette = LocalClearAirPalette.current
    val description = if (length == 0) "Password empty" else "$length characters entered"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Password", style = ClearAirType.label, color = palette.ink2)
        Spacer(Modifier.height(ClearAirSpacing.xxs))
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(FIELD_HEIGHT)
                .border(
                    width = if (focused) 2.dp else 1.5.dp,
                    color = if (focused) palette.ink else palette.ink3,
                    shape = RoundedCornerShape(FIELD_RADIUS),
                )
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = FIELD_PADDING),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (length == 0 && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    modifier = Modifier.padding(start = PLACEHOLDER_START).clearAndSetSemantics {},
                    style = ClearAirType.body,
                    color = palette.ink2,
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = DOT_DIAMETER.toPx() / 2f
                val pitch = DOT_PITCH.toPx()
                val centerY = size.height / 2f
                // Dots fill from the left. Characters past the last slot are not drawn, and the caret stays after the last one drawn.
                val maxVisible = ((size.width + (pitch - 2f * radius)) / pitch).toInt().coerceAtLeast(1)
                val shown = minOf(length, maxVisible)
                for (i in 0 until shown) {
                    drawCircle(color = palette.ink, radius = radius, center = Offset(radius + i * pitch, centerY))
                }
                if (focused) {
                    val half = CARET_HALF.toPx()
                    val x = radius + shown * pitch
                    val caret = Path().apply {
                        moveTo(x, centerY - half)
                        lineTo(x + half, centerY)
                        lineTo(x, centerY + half)
                        lineTo(x - half, centerY)
                        close()
                    }
                    drawPath(caret, color = palette.spark)
                }
            }
        }
    }
}

private val FIELD_HEIGHT = 56.dp
private val FIELD_RADIUS = 16.dp
private val FIELD_PADDING = 16.dp
private val DOT_DIAMETER = 9.dp
private val DOT_PITCH = 18.dp
private val CARET_HALF = 4.5.dp
private val PLACEHOLDER_START = 18.dp
