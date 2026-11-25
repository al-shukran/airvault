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
import app.lumio.data.config.TransactionSettings
import app.lumio.data.config.saveFailureWords
import app.lumio.ui.settings.DisplaySettingsPage
import app.lumio.ui.settings.TransactionsSettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A setting of the open vault as it is published, the notice of a refused change, and the change itself. */
private class SettingChange<T>(
    val settings: T,
    val notice: String?,
    val change: ((T) -> T) -> Unit,
)

/**
 * A setting of the open vault, with a way to change it through [update]. A change is written off the main thread, after the changes made before it, and a
 * write that the vault refuses sets the notice, in the words of the refusal when the stored record is unreadable. Null while the vault is not open.
 */
@Composable
private fun <T> rememberSettingChange(
    navController: NavHostController,
    holder: VaultHolder,
    defaults: T,
    settingOf: (LumioVault) -> StateFlow<T>,
    update: (LumioVault, (T) -> T) -> Unit,
): SettingChange<T>? {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val writes = remember { Mutex() }
    var notice by remember { mutableStateOf<String?>(null) }
    val vault = app.lumioVault()
    val fallback = remember { MutableStateFlow(defaults) }
    val settings by (vault?.let(settingOf) ?: fallback).collectAsState()
    if (vault == null) return null
    return SettingChange(settings, notice) { transform ->
        scope.launch {
            val failure = writes.withLock { withContext(Dispatchers.IO) { runCatching { update(vault, transform) }.exceptionOrNull() } }
            notice = failure?.saveFailureWords("The setting could not be saved. Try again.")
        }
    }
}

/** The Display page of the settings: whether balances show, the currency, the theme and the smallest balance a list shows. */
@Composable
internal fun DisplaySettingsRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    val display = rememberSettingChange(navController, holder, DisplaySettings(), LumioVault::display, LumioVault::updateDisplay) ?: return
    DisplaySettingsPage(
        settings = display.settings,
        notice = display.notice,
        onShowTotal = { on -> display.change { it.copy(showTotal = on) } },
        onShowAssets = { on -> display.change { it.copy(showAssets = on) } },
        onCurrency = { currency -> display.change { it.copy(currency = currency) } },
        onTheme = { theme -> display.change { it.copy(theme = theme) } },
        onSmallBalance = { usd -> display.change { it.copy(smallBalanceUsd = usd) } },
        offline = rememberSettingsOffline(),
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}

/** The Transactions page of the settings: whether spam is hidden, the Last Chance pause, and the backup of the comments on the transactions. */
@Composable
internal fun TransactionsSettingsRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    val transactions = rememberSettingChange(navController, holder, TransactionSettings(), LumioVault::transactions, LumioVault::updateTransactions)
        ?: return
    TransactionsSettingsPage(
        settings = transactions.settings,
        notice = transactions.notice,
        onHideSpam = { on -> transactions.change { it.copy(hideSpam = on) } },
        onLastChance = { seconds -> transactions.change { it.copy(lastChanceSeconds = seconds) } },
        offline = rememberSettingsOffline(),
        onBack = { navController.popBackStack() },
        onLock = onLock,
        comments = { CommentBackupRoute(holder = holder) },
    )
}
