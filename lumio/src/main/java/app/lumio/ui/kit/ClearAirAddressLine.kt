package app.lumio.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.airvault.core.model.AddressGroups
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The project's address style in a row: JetBrains Mono at 13.5 sp on a 20 sp line with no tracking, as a field sets an address. */
val ROW_ADDRESS: TextStyle = ClearAirType.address.copy(fontSize = 13.5.sp, lineHeight = 20.sp, letterSpacing = 0.sp)

/** The gap between a row's header and the address line under it. */
val ADDRESS_LINE_GAP = 2.dp

/** The space between a direction word, such as "From", and the address after it. */
private val LEAD_GAP = ClearAirSpacing.xxs

private const val NO_BREAK = ' '
private const val ZERO_WIDTH_SPACE = '​'

/**
 * [address] as [AddressGroups.short] writes it, with no-break spaces inside each half, so a line can break only after the ellipsis: `0x1593 D398 …`
 * then `3eA2 5343`. A group is never split, and the two ends always read whole.
 */
fun addressLineText(address: String): String {
    val short = AddressGroups.short(address)
    val cut = short.indexOf('…')
    if (cut < 0) return short.replace(' ', NO_BREAK)
    val head = short.substring(0, cut + 1).replace(' ', NO_BREAK)
    val tail = short.substring(cut + 1)
    return if (tail.startsWith(' ')) "$head ${tail.trimStart().replace(' ', NO_BREAK)}" else "$head$ZERO_WIDTH_SPACE$tail"
}

/** What a screen reader says for [address]: its short form with "to" for the ellipsis, as in "0x1593 D398 to 3eA2 5343". */
fun addressLineSpoken(address: String): String = AddressGroups.short(address).replace(" … ", " to ").replace("…", " to ")

/**
 * The short address of a row, on its own line: [AddressGroups.short] in the mono address style, after an optional [lead] word such as "From" in [leadStyle].
 * It is never cut. When it does not fit, it breaks only after the ellipsis, and the second half starts under the first, not under the [lead].
 */
@Composable
fun ClearAirAddressLine(
    address: String,
    modifier: Modifier = Modifier,
    lead: String? = null,
    leadStyle: TextStyle = ClearAirType.caption,
    color: Color = LocalClearAirPalette.current.ink2,
) {
    Row(modifier = modifier) {
        if (lead != null) {
            Text(text = lead, style = leadStyle, color = color, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(LEAD_GAP))
        }
        Text(
            text = addressLineText(address),
            style = ROW_ADDRESS,
            color = color,
            modifier = Modifier.alignByBaseline().semantics { contentDescription = addressLineSpoken(address) },
        )
    }
}
