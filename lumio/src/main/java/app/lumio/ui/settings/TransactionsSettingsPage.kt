package app.lumio.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.data.config.TransactionSettings
import app.lumio.ui.kit.ClearAirAlertNote
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirStopSlider
import app.lumio.ui.kit.ClearAirSwitchRow

/**
 * The Transactions page: whether spam is hidden from Activity, the Last Chance pause before a send is broadcast, and the backup of the transaction
 * comments, which [comments] draws. Each control applies as the owner changes it, and [notice] shows a change that the vault refused.
 */
@Composable
fun TransactionsSettingsPage(
    settings: TransactionSettings,
    notice: String?,
    onHideSpam: (Boolean) -> Unit,
    onLastChance: (Int) -> Unit,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    comments: @Composable () -> Unit,
) {
    SettingsPageScreen(SettingsPage.TRANSACTIONS, offline, onBack, onLock) {
        notice?.let {
            ClearAirAlertNote(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
        }
        ClearAirSwitchRow(
            title = "Hide Spam",
            description = "Keeps spam and scam transfers out of Activity. You can still show them there.",
            checked = settings.hideSpam,
            onCheckedChange = onHideSpam,
        )
        val choices = TransactionSettings.LAST_CHANCE_CHOICES
        SettingsValueLine(title = "Last Chance before Sending", value = "${settings.lastChanceSeconds} s", top = 22.dp)
        ClearAirStopSlider(
            // Only the ends are labelled. The line above carries the value, and the track has a stop for each second.
            labels = choices.map { if (it == choices.first() || it == choices.last()) "$it s" else "" },
            selected = choices.indexOf(settings.lastChanceSeconds).coerceAtLeast(0),
            onSelect = { index -> onLastChance(choices[index]) },
            modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter).padding(top = 6.dp),
        )
        SettingsExplainer(
            text = "After Lensly signs, Lumio waits this long before sending, so you can still stop it. At 0 s it sends at once.",
            modifier = Modifier.padding(top = ClearAirSpacing.xs),
        )
        ClearAirNote(
            text = "The countdown shows on \"Signed by Lensly\", with Send Now and Stop, Don't Send. " +
                "The default is ${TransactionSettings.DEFAULT_LAST_CHANCE_SECONDS} seconds.",
            modifier = Modifier.padding(top = ClearAirSpacing.md),
        )
        comments()
    }
}
