package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lensly.BuildConfig
import app.lensly.app.AppSettings
import app.lensly.app.RegistryHolder
import app.lensly.app.SettingsStore
import app.lensly.app.VaultHolder
import app.lensly.ui.settings.CatalogCounts
import app.lensly.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Settings screen of the hidden graph. */
internal fun NavGraphBuilder.settingsRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.SETTINGS) { HiddenBackdrop { SettingsRoute(navController, holder) } }
}

/**
 * Settings: the saved Bulk Cap, which a tap on an option saves at once, the catalog counts, and the About notices. Every
 * store read and write runs off the main thread, and a closed session returns the owner to the camera.
 */
@Composable
private fun SettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var catalog by remember { mutableStateOf<CatalogCounts?>(null) }

    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            settings = withContext(Dispatchers.IO) { SettingsStore(session).load() }
        }
    }
    // Read again each time the screen is shown, so a package installed from Update catalog is counted.
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        catalog = withContext(Dispatchers.IO) {
            RegistryHolder.get(context).let { CatalogCounts(networks = it.networks().size, tokens = it.tokens().size) }
        }
    }

    SettingsScreen(
        bulkCap = settings?.bulkCap,
        onBulkCap = { cap ->
            scope.launch {
                whileUnlocked(navController, holder) {
                    val next = AppSettings(bulkCap = cap)
                    withContext(Dispatchers.IO) { SettingsStore(session).save(next) }
                    settings = next
                }
            }
        },
        catalog = catalog,
        onUpdateCatalog = { navController.navigate(HiddenRoute.CATALOG) },
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}
