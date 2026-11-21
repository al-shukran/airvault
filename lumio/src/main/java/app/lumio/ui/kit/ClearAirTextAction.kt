package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * The one secondary action of a screen: a text label in ink, underlined, so it reads as an action without a fill. It is at least 48 dp square and
 * scales to 0.98 while pressed.
 */
@Composable
fun ClearAirTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = ClearAirSpacing.touchTarget, minHeight = ClearAirSpacing.touchTarget)
            .lumioPress(
                scale = ClearAirMotion.PRESS_SCALE,
                enabled = enabled,
                releaseSpring = ClearAirMotion.press,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ClearAirType.rowTitle.copy(textDecoration = TextDecoration.Underline),
            color = if (enabled) palette.ink else palette.ink2,
            maxLines = 1,
        )
    }
}
