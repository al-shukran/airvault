package app.lensly.ui.gate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotProgress
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslySpacing

/**
 * Password display: one dot-matrix dot per entered character, on a hairline rule that marks the field. It takes only the
 * character count, so the plaintext never reaches the UI layer. There is deliberately no reveal toggle.
 *
 * When the field is empty it shows a short muted dash. Once the row is full it stays full, and the
 * accessibility description still reports the exact character count. With [onClick] set, a tap calls it, which is how a
 * screen brings its keyboard back.
 */
@Composable
fun PasswordField(
    length: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val description = if (length == 0) "Password empty" else "$length characters entered"
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
    ) {
        val line = RULE_THICKNESS.toPx()
        val dot = DOT_DIAMETER.toPx()
        val pitch = DOT_PITCH.toPx()
        // The dots are centred in the space above the rule.
        val centerY = (size.height - line) / 2f

        drawLine(
            color = LenslyOutlineInteractive,
            start = Offset(0f, size.height - line / 2f),
            end = Offset(size.width, size.height - line / 2f),
            strokeWidth = line,
        )

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

/**
 * The line under a password field. Its height is the same whatever it shows, so the keyboard below never moves.
 *
 * With [working] set, a progress row shows, and [text] sits beside it. Otherwise [text] is shown in [tone], or the line
 * is empty when [text] is null. Its text is announced as it changes.
 */
@Composable
internal fun GateStatusLine(
    text: String?,
    modifier: Modifier = Modifier,
    working: Boolean = false,
    tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) {
            DotProgress(description = text ?: "Working")
            Spacer(Modifier.width(LenslySpacing.sm))
        }
        if (text != null) {
            Text(
                text = text.uppercase(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = tone,
            )
        }
    }
}

private val FIELD_HEIGHT = 40.dp
private val RULE_THICKNESS = 1.dp
private val DOT_DIAMETER = 12.dp
private val DOT_PITCH = 18.dp
private val DASH_WIDTH = 16.dp
private val DASH_HEIGHT = 2.dp
private val STATUS_HEIGHT = 24.dp
