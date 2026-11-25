package app.lumio.ui.kit

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion

private val TRACK_WIDTH = 46.dp
private val TRACK_HEIGHT = 28.dp
private val KNOB = 18.dp

/** The knob's place from the track's edge, with the 1.5 dp edge of an off track counted in: 4 dp inside it, and 3.5 dp from its top. */
private val KNOB_START = 5.5.dp
private val KNOB_END = 22.5.dp
private val KNOB_TOP = 5.dp

/** The knob's travel is the press spring of Clear Air (ζ1.0, k1400): no bounce, so the switch settles as the owner sees it turn. */
private val knobSpring = spring<Dp>(dampingRatio = 1f, stiffness = 1400f)

/**
 * The Clear Air switch: a capsule that is filled with ink when it is on, with the knob on the right, and an outlined capsule with the knob on the left
 * when it is off. It takes no text of its own; [ClearAirSwitchRow] puts the words beside it. Under reduced motion the knob jumps.
 */
@Composable
fun ClearAirSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = LocalClearAirPalette.current
    val knobX by animateDpAsState(
        targetValue = if (checked) KNOB_END else KNOB_START,
        animationSpec = if (LocalReducedMotion.current) snap<Dp>() else knobSpring,
        label = "switch knob",
    )
    Box(
        modifier = modifier
            .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(if (checked) palette.primary else palette.raised)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, palette.ink3, CircleShape)),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobX, y = KNOB_TOP)
                .size(KNOB)
                .clip(CircleShape)
                .background(if (checked) palette.onPrimary else palette.ink3),
        )
    }
}

/**
 * A [ClearAirRow] with a [ClearAirSwitch] at its end, for a setting. The whole row is the switch, so a tap anywhere on it toggles the setting, and a
 * row that is not [enabled] ignores the tap. It takes the row's [leading] disc, [tag], [divider] and [compact] height.
 */
@Composable
fun ClearAirSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    tag: String? = null,
    divider: Boolean = false,
    compact: Boolean = false,
) {
    ClearAirRow(
        title = title,
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleableRow(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange),
        description = description,
        leading = leading,
        trailing = { ClearAirSwitch(checked = checked) },
        tag = tag,
        divider = divider,
        compact = compact,
    )
}

/** The switch row's tap: a toggle with the switch's role, so TalkBack reads it as a switch. */
private fun Modifier.toggleableRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit): Modifier =
    this.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)

/** The opacity of a control that is not available, as the Clear Air disabled capsule draws it. */
private const val DISABLED_ALPHA = 0.4f
