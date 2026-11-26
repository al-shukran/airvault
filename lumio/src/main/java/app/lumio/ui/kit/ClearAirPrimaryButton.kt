package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirCapsule
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of the primary capsule. */
private val CAPSULE_HEIGHT = 52.dp

/** The side padding of a capsule that hugs its label. */
private val HUG_PADDING = 30.dp

/**
 * The one filled action of a screen: a capsule, ink on paper in the light theme and paper on ink in the dark one. It fills the width, or with
 * [fullWidth] false it hugs its label, as Send does on Home. An [icon] sits before the label, set in [labelStyle]. It scales to 0.98 while pressed and gives a light tick. Disabled, it sits on the raised layer with secondary text, and the reason is said beside it, not in it. A [destructive]
 * capsule fills rose when enabled, for a confirmation that erases something.
 */
@Composable
fun ClearAirPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false, icon: ClearAirGlyph? = null, fullWidth: Boolean = true, labelStyle: TextStyle = ClearAirType.rowTitle) {
    val haptics = rememberLumioHaptics()
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
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
            .background(
                when {
                    !enabled -> palette.raised
                    destructive -> palette.rose
                    else -> palette.primary
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleLabel(
            text = text,
            icon = icon,
            color = if (enabled) palette.onPrimary else palette.ink2,
            style = labelStyle,
            modifier = if (fullWidth) Modifier else Modifier.padding(horizontal = HUG_PADDING),
        )
    }
}

/** The label of a capsule: an optional 18 dp [icon], 8 dp apart from the [text], which is set in [style]. */
@Composable
internal fun CapsuleLabel(text: String, icon: ClearAirGlyph?, color: Color, modifier: Modifier = Modifier, style: TextStyle = ClearAirType.rowTitle) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            ClearAirIcon(glyph = it, color = color, size = 18.dp)
            Spacer(Modifier.width(ClearAirSpacing.xs))
        }
        Text(text = text, style = style, color = color, maxLines = 1)
    }
}
