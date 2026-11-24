package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetup
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
 * Lumio's Settings tab: the Finish Setting Up checklist while any of its items is open, then one row per page, in the order of [rows], each with its
 * state under its title. [finish] is what the checklist has ticked, and [onFinish] opens the page of an open item. [onOpen] opens the page of a row.
 * The last row is Reset Lumio, which asks first in a dialog and then calls [onReset]. The network side is not here: the profile panel holds it.
 * Nothing on this list is saved by a Save button, because each page applies its changes at once.
 */
@Composable
fun LumioSettingsScreen(
    rows: List<SettingsRowState>,
    finish: FinishSetup,
    onFinish: (FinishItem) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var resetting by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = ClearAirSpacing.xs),
    ) {
        if (!finish.complete) {
            FinishSetupCard(setup = finish, onOpen = onFinish, modifier = Modifier.padding(bottom = ClearAirSpacing.xs))
        }
        rows.forEach { row ->
            ClearAirRow(title = row.page.title, description = row.summary, onClick = { onOpen(row.page) })
        }
        ClearAirRow(
            title = "Reset Lumio",
            description = "Erase pairings and settings on this phone",
            onClick = { resetting = true },
            destructive = true,
        )
    }
    if (resetting) {
        ResetLumioDialog(onDismiss = { resetting = false }, onConfirm = { resetting = false; onReset() })
    }
}

