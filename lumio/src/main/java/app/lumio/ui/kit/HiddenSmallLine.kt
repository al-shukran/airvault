package app.lumio.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * The count of the balances that Hide Small Balances leaves out of a list, with Show as the text action that lists them, as the activity's spam line
 * does. A list whose every balance is left out shows this line in place of its rows.
 */
@Composable
fun HiddenSmallLine(count: Int, onShow: () -> Unit) {
    val noun = if (count == 1) "small balance" else "small balances"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$count $noun hidden", style = ClearAirType.body, color = LocalClearAirPalette.current.ink2, modifier = Modifier.weight(1f))
        ClearAirTextAction(text = "Show", onClick = onShow)
    }
}
