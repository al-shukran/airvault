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
import app.lumio.ui.settings.DisplaySettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Home section of the settings, read from and written to the display settings of the open vault. */
@Composable
internal fun DisplaySettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    val vault = app.lumioVault() ?: return
    val settings by vault.display.collectAsState()

    // A change is saved off the main thread. The screen shows it once the vault has saved it.
    fun change(transform: (DisplaySettings) -> DisplaySettings) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { vault.updateDisplay(transform) }.isSuccess }
            notice = if (saved) null else "The setting could not be saved. Try again."
        }
    }

    DisplaySettingsSection(
        settings = settings,
        notice = notice,
        onShowTotal = { on -> change { it.copy(showTotal = on) } },
        onShowAssets = { on -> change { it.copy(showAssets = on) } },
        onMinimumUsd = { amount -> change { it.copy(minimumUsd = amount) } },
        onHideSpam = { on -> change { it.copy(hideSpam = on) } },
    )
}
