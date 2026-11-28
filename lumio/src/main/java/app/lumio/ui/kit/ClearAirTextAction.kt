package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The tag of the leading icon, so a test can read where it sits. */
internal const val TEXT_ACTION_ICON_TAG = "text-action-icon"

/**
 * The one secondary action of a screen: a text label in ink with no fill, and an optional [icon] before it or [trailingIcon] after it, 18 dp unless [iconSize] says otherwise. It is at least 48 dp square and
 * scales to 0.98 while pressed. A mock that sets its label at another size passes it as [style], and one that sets the leading icon lower than the label's centre passes the
 * distance as [iconDrop].
 */
@Composable
fun ClearAirTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ClearAirGlyph? = null,
    style: TextStyle = ClearAirType.rowTitle,
    iconSize: Dp = 18.dp,
    iconDrop: Dp = 0.dp,
    trailingIcon: ClearAirGlyph? = null,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (enabled) palette.ink else palette.ink2
            icon?.let {
                ClearAirIcon(glyph = it, color = color, size = iconSize, modifier = Modifier.offset(y = iconDrop).testTag(TEXT_ACTION_ICON_TAG))
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = style, color = color, maxLines = 1)
            trailingIcon?.let {
                Spacer(Modifier.width(6.dp))
                ClearAirIcon(glyph = it, color = color, size = iconSize)
            }
        }
    }
}
