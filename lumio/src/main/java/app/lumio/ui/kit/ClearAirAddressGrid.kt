package app.lumio.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * An address in groups of four, as [groupAddress] writes it: the `0x` stays with the first group, and every second group is dimmed, so the address
 * reads aloud in pairs. Text wraps only at the spaces, so a group is never split.
 */
@Composable
fun ClearAirAddressGrid(address: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val grouped = buildAnnotatedString {
        groupAddress(address).split(' ').forEachIndexed { index, group ->
            if (index > 0) append(' ')
            withStyle(SpanStyle(color = if (index % 2 == 1) palette.ink2 else palette.ink)) { append(group) }
        }
    }
    Text(
        text = grouped,
        modifier = modifier,
        style = ClearAirType.address.copy(fontSize = 14.5.sp, lineHeight = 22.sp),
        color = palette.ink,
    )
}
