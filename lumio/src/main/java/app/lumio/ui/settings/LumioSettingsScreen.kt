package app.lumio.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsRow

/** The pages of Lumio's settings. Each one is a row of the top-level list, and opens its own screen under its [title]. */
enum class SettingsPage(val title: String) {
    DISPLAY("Display"),
    TRANSACTIONS("Transactions"),
    NETWORKS("Networks"),
    PROVIDERS("Data providers"),
    WALLETS("Wallets"),
    ADDRESS_BOOK("Address book"),
    SECURITY("Security"),
    ABOUT("About"),
    DEVELOPER("Developer"),
}

/** One row of the top-level list: the [page] it opens, and the one-line [summary] that shows the page's state under its title. */
data class SettingsRowState(val page: SettingsPage, val summary: String)

/**
 * Lumio's settings: one chevron row per page, in the order of [rows]. [onOpen] opens the page of a row. The Developer row is in [rows] only in a debug
 * build, so it is the last row there and absent in release. Nothing on this list is a setting, so it has no Save: each page applies its controls at once.
 * [onBack] is the back arrow, which returns to Home.
 */
@Composable
fun LumioSettingsScreen(rows: List<SettingsRowState>, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LenslyScreen(title = "Settings", modifier = modifier, onBack = onBack) {
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
