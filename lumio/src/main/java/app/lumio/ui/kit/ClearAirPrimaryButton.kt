package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirCapsule
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of the primary capsule. */
private val CAPSULE_HEIGHT = 56.dp

/**
 * The one filled action of a screen: a full-width capsule, ink on paper in the light theme and paper on ink in the dark one. It scales to 0.98 while
 * pressed and gives a light tick. Disabled, it sits on the raised layer with secondary text, and the reason is said beside it, not in it.
 */
@Composable
fun ClearAirPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = rememberLumioHaptics()
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CAPSULE_HEIGHT)
            .lumioPress(
                scale = ClearAirMotion.PRESS_SCALE,
                enabled = enabled,
                shape = ClearAirCapsule,
                releaseSpring = ClearAirMotion.press,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .background(if (enabled) palette.primary else palette.raised),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ClearAirType.rowTitle,
            color = if (enabled) palette.onPrimary else palette.ink2,
            maxLines = 1,
        )
    }
}
