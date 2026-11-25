package app.lumio.ui.settings

import app.lumio.DebugDemoData
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.ThemeMode
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.kit.LocalFiat

/** The Display page: what the wallet screens show. Each control applies as the owner changes it, and [notice] shows a change that the vault refused. */
@Composable
fun DisplaySettingsPage(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onCurrency: (FiatCurrency) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onSmallBalance: (Double) -> Unit,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    // The sheets belong to the page, not to its scrolling column: a sheet needs the page's bounds to cover it.
    var picker by rememberSaveable { mutableStateOf(if (DebugDemoData.flag("currency")) DisplayPicker.CURRENCY else null) }
    SettingsPageScreen(
        SettingsPage.DISPLAY,
        offline,
        onBack,
        onLock,
        sheet = {
            DisplaySheets(
                settings = settings,
                picker = picker,
                onClose = { picker = null },
                onCurrency = onCurrency,
                onTheme = onTheme,
            )
        },
    ) {
        DisplaySettingsSection(
            settings = settings,
            notice = notice,
            onShowTotal = onShowTotal,
            onShowAssets = onShowAssets,
            onOpenCurrency = { picker = DisplayPicker.CURRENCY },
            onOpenTheme = { picker = DisplayPicker.THEME },
            onSmallBalance = onSmallBalance,
        )
    }
}

/** The radio sheet the Display page has open: at most one. */
private enum class DisplayPicker { CURRENCY, THEME }

/**
 * The Display settings: the Show Total Balance and Show Individual Asset Balances switches, the Currency and Theme rows, which each ask the page to open its radio sheet ([DisplaySheets]), and the Hide Small
 * Balances slider. Each control reports its new value at once. [notice] shows a change that the vault refused.
 */
@Composable
fun DisplaySettingsSection(
    settings: DisplaySettings,
    notice: String?,
    onShowTotal: (Boolean) -> Unit,
    onShowAssets: (Boolean) -> Unit,
    onOpenCurrency: () -> Unit,
    onOpenTheme: () -> Unit,
    onSmallBalance: (Double) -> Unit,
) {
    val palette = LocalClearAirPalette.current
    notice?.let {
        ClearAirAlertNote(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
    }
    ClearAirSwitchRow(
        title = "Show Total Balance",
        description = "Shows the combined balance of your assets",
        checked = settings.showTotal,
        onCheckedChange = onShowTotal,
    )
    ClearAirSwitchRow(
        title = "Show Individual Asset Balances",
        divider = true,
        description = "Shows each asset's balance",
        checked = settings.showAssets,
        onCheckedChange = onShowAssets,
    )
    ClearAirRow(
        title = "Currency",
        divider = true,
        description = "Prices and totals",
        trailing = { Text(text = settings.currency.code, style = ClearAirType.address, color = palette.ink) },
        onClick = onOpenCurrency,
    )
    ClearAirRow(
        title = "Theme",
        divider = true,
        description = "Light, dark or as the phone is set",
        trailing = { Text(text = settings.theme.title, style = ClearAirType.small, color = palette.ink) },
        onClick = onOpenTheme,
    )
    val stops = DisplaySettings.SMALL_BALANCE_STOPS
    // The threshold is held in US dollars and shown in the chosen currency, which falls back to US dollars while no rate is known.
    val threshold = LocalFiat.current.amount(settings.smallBalanceUsd)
    SettingsValueLine(title = "Hide Small Balances", value = threshold, top = 22.dp)
    ClearAirStopSlider(
        // Only the ends are labelled. The line above carries the value, and the track has a stop for each fifty cents.
        labels = stops.map { if (it == stops.first() || it == stops.last()) "\$${it.toInt()}" else "" },
        selected = stops.indexOf(settings.smallBalanceUsd).coerceAtLeast(0),
        onSelect = { index -> onSmallBalance(stops[index]) },
        modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter).padding(top = 6.dp),
    )
    SettingsExplainer(
        text = if (settings.smallBalanceUsd > 0.0) {
            "Assets worth less than $threshold are left out of balance lists. At \$0, everything shows."
        } else {
            "Every asset shows in balance lists."
        },
        modifier = Modifier.padding(top = ClearAirSpacing.xs),
    )
}

/** The Currency and Theme radio sheets over the Display page. A choice applies and closes the sheet. */
@Composable
private fun DisplaySheets(
    settings: DisplaySettings,
    picker: DisplayPicker?,
    onClose: () -> Unit,
    onCurrency: (FiatCurrency) -> Unit,
    onTheme: (ThemeMode) -> Unit,
) {
    ClearAirSheet(visible = picker == DisplayPicker.CURRENCY, onClose = onClose) {
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
                                onClose()
                                onCurrency(currency)
                            },
                        )
                    },
                ),
            ),
        )
    }
    ClearAirSheet(visible = picker == DisplayPicker.THEME, onClose = onClose) {
        ClearAirChoiceSheet(
            title = "Theme",
            groups = listOf(
                ChoiceGroup(
                    null,
                    ThemeMode.entries.map { mode ->
                        ChoiceRow(
                            title = mode.title,
                            selected = mode == settings.theme,
                            onPick = {
                                onClose()
                                onTheme(mode)
                            },
                        )
                    },
                ),
            ),
        )
    }
}
