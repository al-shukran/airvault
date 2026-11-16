package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettings
import app.lumio.ui.settings.DisplaySettingsPage
import app.lumio.ui.settings.TransactionsSettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The display settings as the open vault publishes them, the notice of a refused change, and the change itself. */
private class DisplayChange(
    val settings: DisplaySettings,
    val notice: String?,
    val change: ((DisplaySettings) -> DisplaySettings) -> Unit,
)

/**
 * The display settings of the open vault, with a way to change them. A change is written off the main thread, after the changes made before it, and a
 * write that the vault refuses sets the notice. Null while the vault is not open.
 */
@Composable
private fun rememberDisplayChange(navController: NavHostController, holder: VaultHolder): DisplayChange? {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val writes = remember { Mutex() }
    var notice by remember { mutableStateOf<String?>(null) }
    val vault = app.lumioVault()
    val fallback = remember { MutableStateFlow(DisplaySettings()) }
    val settings by (vault?.display ?: fallback).collectAsState()
    if (vault == null) return null
    return DisplayChange(settings, notice) { transform ->
        scope.launch {
            val saved = writes.withLock { withContext(Dispatchers.IO) { runCatching { vault.updateDisplay(transform) }.isSuccess } }
            notice = if (saved) null else "The setting could not be saved. Try again."
        }
    }
}

/** The Display page of the settings: what Home shows. */
@Composable
internal fun DisplaySettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val display = rememberDisplayChange(navController, holder) ?: return
    DisplaySettingsPage(
        settings = display.settings,
        notice = display.notice,
        onShowTotal = { on -> display.change { it.copy(showTotal = on) } },
        onShowAssets = { on -> display.change { it.copy(showAssets = on) } },
        onMinimumUsd = { amount -> display.change { it.copy(minimumUsd = amount) } },
        onBack = { navController.popBackStack() },
    )
}

/** The Transactions page of the settings: the spam filter, then the backup of the comments. */
@Composable
internal fun TransactionsSettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val display = rememberDisplayChange(navController, holder) ?: return
    TransactionsSettingsPage(
        hideSpam = display.settings.hideSpam,
        notice = display.notice,
        onHideSpam = { on -> display.change { it.copy(hideSpam = on) } },
        onBack = { navController.popBackStack() },
        comments = { CommentBackupRoute(holder = holder) },
    )
}
