package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lensly.app.VaultHolder
import app.lensly.app.entryGestureHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettingsStore
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkModeStore
import app.lumio.pair.PairedWallets
import app.lumio.ui.settings.AboutSettingsPage
import app.lumio.ui.settings.AddressBookRows
import app.lumio.ui.settings.AddressBookSettingsPage
import app.lumio.ui.settings.LumioSettingsScreen
import app.lumio.ui.settings.NetworksSettingsPage
import app.lumio.ui.settings.SecuritySettingsPage
import app.lumio.ui.settings.SettingsFacts
import app.lumio.ui.settings.WalletsSettingsPage
import app.lumio.ui.settings.providerCounts
import app.lumio.ui.settings.settingsRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Settings tab: the network side at its top, then one row per page, each with its state under its title. The state is read from the open vault again
 * each time the list is shown, so a change made on a page shows here when the owner comes back to it. The side applies as the owner picks it.
 */
@Composable
internal fun SettingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int, selection: NetworkSelection) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val vault = app.lumioVault()
    val shown by navController.currentBackStackEntryAsState()
    val facts by produceState<SettingsFacts?>(initialValue = null, session, listVersion, shown) {
        value = if (session == null || vault == null) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { readFacts(vault, session) }.getOrNull() }
        }
    }
    val gesture = LocalContext.current.entryGestureHolder().current
    val current = facts ?: return
    val scope = rememberCoroutineScope()
    LumioSettingsScreen(
        rows = settingsRows(current, gesture, BuildConfig.VERSION_NAME),
        onOpen = { page -> navController.navigate(LumioRoute.settingsPage(page)) },
        network = selection.mode,
        onNetwork = { next ->
            selection.mode = next
            if (session != null) scope.launch { onVault(Unit) { NetworkModeStore.write(session, next) } }
        },
    )
}

/** What the list shows, read from the open vault and its session. Runs off the main thread. */
private fun readFacts(vault: LumioVault, session: VaultSession): SettingsFacts {
    val configs = vault.configs()
    val (providersOn, providersTotal) = providerCounts(configs, BuildConfig.DEBUG)
    val wallets = PairedWallets.list(session)
    return SettingsFacts(
        display = DisplaySettingsStore.read(session),
        chainsOn = EvmSettingsStore.read(session).enabled.size,
        customBitcoin = serverOverrides(configs).isNotEmpty(),
        providersOn = providersOn,
        providersTotal = providersTotal,
        wallets = wallets.size,
        addresses = wallets.sumOf { it.addressBook.size },
    )
}

/** The routes of the pages below the list. Each one is a Hidden Area screen, with the testnet banner like every Lumio screen. */
internal fun NavGraphBuilder.settingsPageRoutes(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onChanged: () -> Unit,
    lockChoices: LockSettingsState,
) {
    composable(LumioRoute.SETTINGS_DISPLAY) {
        LumioBackdrop(selection) { DisplaySettingsRoute(navController = navController, holder = holder) }
    }
    composable(LumioRoute.SETTINGS_TRANSACTIONS) {
        LumioBackdrop(selection) { TransactionsSettingsRoute(navController = navController, holder = holder) }
    }
    composable(LumioRoute.SETTINGS_NETWORKS) {
        LumioBackdrop(selection) { NetworksSettingsRoute(navController = navController, holder = holder) }
    }
    composable(LumioRoute.SETTINGS_WALLETS) {
        LumioBackdrop(selection) {
            WalletsSettingsPage(
                onBack = { navController.popBackStack() },
                wallets = { WalletsSettingsRoute(navController = navController, holder = holder, listVersion = listVersion, onChanged = onChanged) },
            )
        }
    }
    composable(LumioRoute.SETTINGS_ADDRESSES) {
        LumioBackdrop(selection) {
            AddressBookSettingsRoute(navController = navController, holder = holder, listVersion = listVersion)
        }
    }
    composable(LumioRoute.SETTINGS_SECURITY) {
        LumioBackdrop(selection) { SecuritySettingsRoute(navController = navController, lockChoices = lockChoices) }
    }
    composable(LumioRoute.SETTINGS_ABOUT) {
        LumioBackdrop(selection) { AboutSettingsRoute(navController = navController) }
    }
}

/** The Networks page: the Bitcoin servers and the Ethereum chains, each read and written by its own route. */
@Composable
private fun NetworksSettingsRoute(navController: NavHostController, holder: VaultHolder) {
    NetworksSettingsPage(
        onBack = { navController.popBackStack() },
        bitcoin = { BitcoinSettingsRoute(navController = navController, holder = holder) },
        ethereum = { EvmSettingsRoute(navController = navController, holder = holder) },
    )
}

/** The Address book page: each paired wallet, which opens that wallet's address book. The wallets are read again when the list changes. */
@Composable
private fun AddressBookSettingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int) {
    val wallets = pairedWallets(navController = navController, holder = holder, listVersion = listVersion)
    AddressBookSettingsPage(
        onBack = { navController.popBackStack() },
        wallets = {
            AddressBookRows(wallets = wallets, onAddressBook = { wallet -> navController.navigate(LumioRoute.addressBook(wallet.id)) })
        },
    )
}

/**
 * The Security page: the Hidden Entry Gesture, whose row opens the choice shared through :shared, the App Password, whose row opens its
 * change, and the lock. Each choice applies as the owner makes it.
 */
@Composable
private fun SecuritySettingsRoute(navController: NavHostController, lockChoices: LockSettingsState) {
    SecuritySettingsPage(
        entryGesture = LocalContext.current.entryGestureHolder().current,
        lock = lockChoices.settings,
        onEntryGesture = { navController.navigate(LumioRoute.ENTRY_GESTURE) },
        onChangePassword = { navController.navigate(LumioRoute.CHANGE_PASSWORD) },
        onIdleMinutes = { minutes -> lockChoices.change { it.copy(idleMinutes = minutes) } },
        onLockWhenSwitching = { on -> lockChoices.change { it.copy(lockWhenSwitchingApps = on) } },
        onBack = { navController.popBackStack() },
    )
}

/** The About page: the version of this build, and the licences it ships under. */
@Composable
private fun AboutSettingsRoute(navController: NavHostController) {
    AboutSettingsPage(
        version = BuildConfig.VERSION_NAME,
        onLicences = { navController.navigate(LumioRoute.LICENCES) },
        onBack = { navController.popBackStack() },
    )
}
