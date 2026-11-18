package app.lumio.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion

/**
 * The press and the tap of every tappable Lumio surface (section 3.4). The surface shrinks to [scale] and the ripple starts on the frame of the
 * touch-down, and both let go on the release, or when a scroll takes the touch. Compose's clickable waits about 40 ms inside a scrollable before it
 * shows a press, so this modifier reads the touch itself and feeds the press to the ripple at once. [shape] clips the ripple to the surface.
 */
fun Modifier.lumioPress(
    scale: Float,
    onClick: () -> Unit,
    enabled: Boolean = true,
    shape: Shape? = null,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val press = remember { Animatable(1f) }
    val reduced = LocalReducedMotion.current
    val currentOnClick by rememberUpdatedState(onClick)
    this
        .graphicsLayer {
            scaleX = press.value
            scaleY = press.value
        }
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .indication(interaction, LocalIndication.current)
        .pointerInput(enabled, scale, reduced) {
            if (enabled) {
                detectTapGestures(
                    onPress = { position ->
                        val pressed = PressInteraction.Press(position)
                        press.snapTo(scale)
                        interaction.emit(pressed)
                        val released = tryAwaitRelease()
                        interaction.emit(if (released) PressInteraction.Release(pressed) else PressInteraction.Cancel(pressed))
                        if (reduced) press.snapTo(1f) else press.animateTo(1f, LumenMotion.spatialFast)
                    },
                    onTap = { currentOnClick() },
                )
            }
        }
        .semantics {
            role = Role.Button
            if (enabled) onClick(label = null, action = { currentOnClick(); true }) else disabled()
        }
}
