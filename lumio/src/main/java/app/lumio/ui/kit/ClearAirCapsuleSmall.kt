package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirCapsule
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.LocalClearAirPalette

/** The visible height of the compact capsule, and the 48 dp touch target it sits in. */
private val CAPSULE_HEIGHT = 44.dp
private val TOUCH_HEIGHT = 48.dp

/** The side padding of the compact capsule's label. */
private val CAPSULE_PADDING = 22.dp

/**
 * The compact capsule: a filled action beside another action, such as Send beside Receive under a total. It is 44 dp high inside a 48 dp touch target,
 * scales to 0.98 while pressed, and gives a light tick. Disabled, it sits on the raised layer with secondary text, and the reason is said beside it. An [icon] sits before the label.
 */
@Composable
fun ClearAirCapsuleSmall(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ClearAirGlyph? = null) {
    val haptics = rememberLumioHaptics()
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier
            .height(TOUCH_HEIGHT)
            .lumioPress(
                scale = ClearAirMotion.PRESS_SCALE,
                enabled = enabled,
                releaseSpring = ClearAirMotion.press,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(CAPSULE_HEIGHT)
                .background(color = if (enabled) palette.primary else palette.raised, shape = ClearAirCapsule)
                .padding(horizontal = CAPSULE_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            CapsuleLabel(text = text, icon = icon, color = if (enabled) palette.onPrimary else palette.ink2)
        }
    }
}
