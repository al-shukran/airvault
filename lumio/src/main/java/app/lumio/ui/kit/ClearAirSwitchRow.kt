package app.lumio.ui.kit

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion

/** The height of a switch row with a one-line subtitle, as the rows of a settings page are drawn. */
private val ROW_HEIGHT = 62.dp

private val TRACK_WIDTH = 51.dp
private val TRACK_HEIGHT = 31.dp
private val KNOB = 27.dp
private val KNOB_INSET = 2.dp

/** The knob's travel is a spring with no bounce, so the switch settles as the owner sees it turn. */
private val knobSpring = spring<Dp>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumHigh)

/**
 * The Clear Air switch: a capsule that is filled with ink when it is on, with the knob on the right, and an outlined capsule with the knob on the left
 * when it is off. It takes no text of its own; [ClearAirSwitchRow] puts the words beside it. Under reduced motion the knob jumps.
 */
@Composable
fun ClearAirSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = LocalClearAirPalette.current
    val knobX by animateDpAsState(
        targetValue = if (checked) TRACK_WIDTH - KNOB - KNOB_INSET else KNOB_INSET,
        animationSpec = if (LocalReducedMotion.current) snap<Dp>() else knobSpring,
        label = "switch knob",
    )
    Box(
        modifier = modifier
            .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(if (checked) palette.primary else palette.raised)
            .then(if (checked) Modifier else Modifier.border(1.dp, palette.hairline, CircleShape)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobX, y = KNOB_INSET)
                .size(KNOB)
                .clip(CircleShape)
                .background(if (checked) palette.onPrimary else palette.ink2),
        )
    }
}

/**
 * A row of a settings page with a [title], an optional [description] under it, and a [ClearAirSwitch] at its end. The whole row is the switch, so a tap
 * anywhere on it toggles the setting, and a row that is not [enabled] ignores the tap.
 */
@Composable
fun ClearAirSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = ClearAirSpacing.gutter)
            .toggleableRow(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = ClearAirType.rowTitle,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(text = description, style = ClearAirType.body, color = palette.ink2)
            }
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        ClearAirSwitch(checked = checked)
    }
}

/** The switch row's tap: a toggle with the switch's role, so TalkBack reads it as a switch. */
private fun Modifier.toggleableRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit): Modifier =
    this.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)

/** The opacity of a control that is not available, as the Clear Air disabled capsule draws it. */
private const val DISABLED_ALPHA = 0.4f
