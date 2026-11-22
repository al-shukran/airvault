package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.kit.ClearAirRow

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
 * Lumio's Settings tab: one row per page, in the order of [rows], each with its state under its title. [onOpen] opens the page of a row. The network
 * side is not here: the profile panel holds it. Nothing on this list is saved by a Save button, because each page applies its changes at once.
 */
@Composable
fun LumioSettingsScreen(rows: List<SettingsRowState>, onOpen: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = ClearAirSpacing.xs),
    ) {
        rows.forEach { row ->
            ClearAirRow(title = row.page.title, description = row.summary, onClick = { onOpen(row.page) })
        }
    }
}

/** The frame of one settings page: its title, with a back arrow to the list. The page's controls apply as the owner changes them. */
@Composable
fun SettingsPageScreen(page: SettingsPage, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LenslyScreen(title = page.title, onBack = onBack, content = content)
}
