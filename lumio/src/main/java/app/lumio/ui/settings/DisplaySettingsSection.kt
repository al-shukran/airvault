package app.lumio.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.SettingsSwitchRow
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.config.DisplaySettings

/** The Display settings: what the wallet screens show. Each switch reports its new value at once. [notice] shows a change that the vault refused. */
@Composable
fun DisplaySettingsSection(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
) {
    notice?.let {
        InlineBanner(message = it, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.sm))
    }
    SettingsSwitchRow(
        title = "Show Total Balance",
        description = "Shows the combined balance of your assets.",
        checked = settings.showTotal,
        onCheckedChange = onShowTotal,
    )
    SettingsSwitchRow(
        title = "Show Individual Asset Balances",
        description = "Shows each asset's balance.",
        checked = settings.showAssets,
        onCheckedChange = onShowAssets,
    )
}
