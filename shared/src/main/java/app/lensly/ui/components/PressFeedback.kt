package app.lensly.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val PRESSED_SCALE = 0.96f
private const val PRESSED_ALPHA = 0.85f
private const val DISABLED_ALPHA = 0.38f

/**
 * Press feedback for a button: it shrinks slightly and dims while [interaction] reports a press, and it dims when
 * [enabled] is false. Pass the same [InteractionSource] that the button's clickable uses.
 */
@Composable
fun Modifier.pressFeedback(interaction: InteractionSource, enabled: Boolean): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) PRESSED_SCALE else 1f, label = "press-scale")
    val alpha by animateFloatAsState(
        targetValue = when {
            !enabled -> DISABLED_ALPHA
            pressed -> PRESSED_ALPHA
            else -> 1f
        },
        label = "press-alpha",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}
