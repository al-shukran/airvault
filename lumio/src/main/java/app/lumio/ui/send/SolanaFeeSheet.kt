package app.lumio.ui.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirSwitchRow

/**
 * The fee sheet of a Solana send: the network fee, and one Priority Fee switch, off by default. Solana has no fee stops, so the switch takes the place of
 * the Bitcoin and EVM slider. Nothing is saved: when the sheet closes, the switch's state is passed to [onApply], and the send is planned again with it.
 */
@Composable
fun SolanaFeeSheet(
    visible: Boolean,
    networkFee: String,
    timing: String,
    priority: Boolean,
    onApply: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    var on by remember(visible, priority) { mutableStateOf(priority) }
    ClearAirSheet(
        visible = visible,
        onClose = {
            onApply(on)
            onClose()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(ClearAirSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Network Fee", style = ClearAirType.titleL, color = palette.ink, modifier = Modifier.weight(1f))
                Text(text = networkFee, style = ClearAirType.rowValue, color = palette.ink)
            }
            Text(text = timing, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
            Spacer(Modifier.height(ClearAirSpacing.lg))
            ClearAirSwitchRow(title = "Priority Fee", checked = on, onCheckedChange = { on = it })
            Spacer(Modifier.height(ClearAirSpacing.md))
            Text(text = "Changes apply when you close this sheet.", style = ClearAirType.body, color = palette.ink2)
        }
    }
}
