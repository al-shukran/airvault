package app.lumio.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.data.config.DisplaySettings
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirSwitchRow

/** The Display settings: what the wallet screens show. Each switch reports its new value at once. [notice] shows a change that the vault refused. */
@Composable
fun DisplaySettingsSection(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
) {
    notice?.let {
        ClearAirAlertNote(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
    }
    ClearAirSwitchRow(
        title = "Show Total Balance",
        description = "Shows the combined balance of your assets.",
        checked = settings.showTotal,
        onCheckedChange = onShowTotal,
    )
    ClearAirSwitchRow(
        title = "Show Individual Asset Balances",
        description = "Shows each asset's balance.",
        checked = settings.showAssets,
        onCheckedChange = onShowAssets,
    )
}
