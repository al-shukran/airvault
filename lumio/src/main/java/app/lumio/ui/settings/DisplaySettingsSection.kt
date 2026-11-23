package app.lumio.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.FiatCurrency
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirSwitchRow

/**
 * The Display settings: what the wallet screens show. Each switch, choice and slider reports its new value at once. [notice] shows a change that the
 * vault refused. The Currency row opens its radio sheet, and a choice applies and closes it.
 */
@Composable
fun DisplaySettingsSection(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onCurrency: (FiatCurrency) -> Unit,
    onSmallBalance: (Double) -> Unit,
) {
    var currencyOpen by rememberSaveable { mutableStateOf(false) }
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
    ClearAirRow(
        title = "Currency",
        description = "Prices and totals",
        trailing = { Text(text = settings.currency.code, style = ClearAirType.label, color = LocalClearAirPalette.current.ink2) },
        onClick = { currencyOpen = true },
    )
    ClearAirSheet(visible = currencyOpen, onClose = { currencyOpen = false }) {
        ClearAirChoiceSheet(
            title = "Currency",
            groups = listOf(
                ChoiceGroup(
                    null,
                    FiatCurrency.entries.map { currency ->
                        ChoiceRow(
                            title = currency.title,
                            detail = if (currency.symbol == currency.code) currency.code else "${currency.code} · ${currency.symbol}",
                            selected = currency == settings.currency,
                            onPick = {
                                currencyOpen = false
                                onCurrency(currency)
                            },
                        )
                    },
                ),
            ),
        )
    }
    val stops = DisplaySettings.SMALL_BALANCE_STOPS
    val threshold = FiatCurrency.USD.format(settings.smallBalanceUsd)
    SettingsGroup(title = "Hide Small Balances · $threshold") {
        ClearAirStopSlider(
            // Only the ends are labelled. The title above carries the value, and the track has a stop for each fifty cents.
            labels = stops.map { if (it == stops.first() || it == stops.last()) "\$${it.toInt()}" else "" },
            selected = stops.indexOf(settings.smallBalanceUsd).coerceAtLeast(0),
            onSelect = { index -> onSmallBalance(stops[index]) },
            modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
        )
        ClearAirNote(
            text = if (settings.smallBalanceUsd > 0.0) {
                "Assets worth less than $threshold are left out of balance lists. At \$0, everything shows."
            } else {
                "Every asset shows in balance lists."
            },
            modifier = Modifier.padding(top = ClearAirSpacing.xs).padding(horizontal = ClearAirSpacing.gutter),
        )
    }
}
