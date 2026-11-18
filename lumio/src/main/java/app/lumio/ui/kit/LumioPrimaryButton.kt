package app.lumio.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lumio.ui.theme.LumioFullShape
import app.lumio.ui.theme.LumioMotion

/** The height of the primary button (section 1.3). */
private val BUTTON_HEIGHT = 56.dp

/** How far a pressed primary button shrinks (section 3.4). */
private const val PRESSED_BUTTON_SCALE = 0.97f

/**
 * The one filled action of a screen (control rule 1): a full-width gold capsule. Disabled, it sits on the raised surface with muted text, and the
 * reason is said beside it, not in it. It scales to 0.97 while pressed.
 */
@Composable
fun LumioPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled) PRESSED_BUTTON_SCALE else 1f,
        animationSpec = LumioMotion.spatialFast,
        label = "primary press",
    )
    val container = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .clip(LumioFullShape)
            .background(container)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}
