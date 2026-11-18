package app.lumio.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing
import app.lumio.network.NetworkMode
import app.lumio.ui.network.NetworkSwitch

/** The pages of Lumio's settings. Each one is a row of the top-level list, and opens its own screen under its [title]. */
enum class SettingsPage(val title: String) {
    DISPLAY("Display"),
    TRANSACTIONS("Transactions"),
    NETWORKS("Networks"),
    PROVIDERS("Data Providers"),
    WALLETS("Wallets"),
    ADDRESS_BOOK("Address Book"),
    SECURITY("Security"),
    ABOUT("About"),
}

/** One row of the top-level list: the [page] it opens, and the one-line [summary] that shows the page's state under its title. */
data class SettingsRowState(val page: SettingsPage, val summary: String)

/**
 * Lumio's settings: the side of the networks at the top, as a Mainnet and Testnet segmented control, then one chevron row per page, in the order of
 * [rows]. [onOpen] opens the page of a row, and [onNetwork] applies the side the owner picks. Nothing on this list is saved by a Save button: each control
 * applies at once. Back returns to Wallets, from which the gear opened the list.
 */
@Composable
fun LumioSettingsScreen(
    rows: List<SettingsRowState>,
    onOpen: (SettingsPage) -> Unit,
    network: NetworkMode,
    onNetwork: (NetworkMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Settings", onBack = onBack, modifier = modifier) {
        Text(
            text = "Network",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        NetworkSwitch(mode = network, onChange = onNetwork)
        Spacer(Modifier.height(LenslySpacing.md))
        rows.forEach { row ->
            SettingsRow(title = row.page.title, description = row.summary, onClick = { onOpen(row.page) })
        }
    }
}

/** The frame of one settings page: its title, with a back arrow to the list. The page's controls apply as the owner changes them. */
@Composable
fun SettingsPageScreen(page: SettingsPage, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LenslyScreen(title = page.title, onBack = onBack, content = content)
}
