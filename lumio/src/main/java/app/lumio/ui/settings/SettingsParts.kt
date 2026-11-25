package app.lumio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow

/**
 * A group line of a settings page: its [title] as an eyebrow, and at its end [value]. A live value, such as the Hide Small Balances threshold, is in
 * the figures face and in ink. A count, such as "12 on", passes [valueMono] false and reads as plain secondary text.
 */
@Composable
internal fun SettingsValueLine(title: String, value: String, top: Dp, valueMono: Boolean = true) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = top, bottom = ClearAirSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) { ClearAirEyebrow(text = title, top = 0.dp, bottom = 0.dp) }
        if (valueMono) {
            Text(text = value, style = ClearAirType.rowValue.copy(fontSize = 15.sp), color = palette.ink)
        } else {
            Text(text = value, style = ClearAirType.small, color = palette.ink2)
        }
    }
}

/** The plain secondary text under a control, which says what the control does. */
@Composable
internal fun SettingsExplainer(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
        style = ClearAirType.small,
        color = LocalClearAirPalette.current.ink2,
    )
}

/** A hairline between two rows, from [start] to the right gutter. */
@Composable
internal fun SettingsDivider(start: Dp = ClearAirSpacing.gutter) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = start, end = ClearAirSpacing.gutter)
            .height(1.dp)
            .background(LocalClearAirPalette.current.hairline),
    )
}

/** The line of a group that has no value: its [title] as an eyebrow, [top] below the last thing above it. */
@Composable
internal fun SettingsHeading(title: String, top: Dp) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = top, bottom = ClearAirSpacing.xxs)) {
        ClearAirEyebrow(text = title, top = 0.dp, bottom = 0.dp)
    }
}

/** A line of facts under a title: its [label] in ink2 at the start, its [value] in bold ink at the end, and a hairline under it unless it is [last]. */
@Composable
internal fun SettingsFact(label: String, value: String, last: Boolean = false) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = ClearAirType.body, color = palette.ink2)
            Text(text = value, style = ClearAirType.rowTitle, color = palette.ink, textAlign = TextAlign.End)
        }
        if (!last) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
    }
}
