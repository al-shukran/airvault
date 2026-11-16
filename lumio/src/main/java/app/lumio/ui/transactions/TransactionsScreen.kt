package app.lumio.ui.transactions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.LenslyScreen

/** The Transactions tab. Its list is not built yet, so the tab says that it is coming. */
@Composable
fun TransactionsScreen(modifier: Modifier = Modifier) {
    LenslyScreen(title = "Transactions", modifier = modifier, onBack = null, scrollable = false) {
        EmptyState(
            icon = LenslyIcons.History,
            title = "Coming soon",
            body = "The transactions of every paired wallet will be listed here.",
        )
    }
}
