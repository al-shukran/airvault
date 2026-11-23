package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lensly.app.VaultHolder
import app.lensly.app.entryGestureHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettingsStore
import app.lumio.data.config.FinishSetupStore
import app.lumio.evm.EvmSettingsStore
import app.lumio.pair.PairedWallets
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.settings.AboutSettingsPage
import app.lumio.ui.settings.AddressBookRows
import app.lumio.ui.settings.AddressBookSettingsPage
import app.lumio.ui.settings.LumioSettingsScreen
import app.lumio.ui.settings.NetworksSettingsPage
import app.lumio.ui.settings.SecuritySettingsPage
import app.lumio.ui.settings.SettingsFacts
import app.lumio.ui.settings.WalletsSettingsPage
import app.lumio.ui.settings.page
import app.lumio.ui.settings.providerCounts
import app.lumio.ui.settings.settingsRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext


/** The Settings tab's route: the root of the tab, with the list of pages in it. */
internal fun NavGraphBuilder.settingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int, selection: NetworkSelection, onLock: () -> Unit) {
    lumioComposable(LumioRoute.SETTINGS) {
        LumioBackdrop(selection) {
            SettingsRoute(navController = navController, holder = holder, listVersion = listVersion, selection = selection, onLock = onLock)
        }
    }
}

/**
 * The Settings tab: one row per page, each with its state under its title. The state comes from the open vault's [SettingsFactsHolder], read when the
 * vault opened, so the list is whole on its first frame. It is read again off the main thread each time the list comes to the top or the paired
 * wallets change, so a change made on a page shows here when the owner comes back to it.
 */
@Composable
private fun SettingsRoute(navController: NavHostController, holder: VaultHolder, listVersion: Int, selection: NetworkSelection, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val store = app.lumioVault()?.settingsFacts ?: return
    val top by navController.currentBackStackEntryAsState()
    val onTop by remember { derivedStateOf { top?.destination?.route == LumioRoute.SETTINGS } }
    LaunchedEffect(store, listVersion, onTop) {
        if (onTop) withContext(Dispatchers.IO) { store.refresh() }
    }
    val cached by store.facts.collectAsState()
    // The vault's own read has not finished yet: read here once, so the list never comes in blank.
    val current = cached ?: remember(store) { store.refresh(); store.facts.value } ?: return
    val gesture = LocalContext.current.entryGestureHolder().current
    TabRoute(tab = LumioTab.SETTINGS, navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = onLock) {
        LumioSettingsScreen(
            rows = settingsRows(current, gesture, BuildConfig.VERSION_NAME),
            finish = current.finish,
            onFinish = { item -> navController.navigate(LumioRoute.settingsPage(item.page)) },
            onOpen = { page -> navController.navigate(LumioRoute.settingsPage(page)) },
        )
    }
}

/** The facts of the Settings list for one open vault. [LumioVault] keeps one, so the list has them before it is first shown. */
internal class SettingsFactsHolder(private val vault: LumioVault) {
    private val state = MutableStateFlow<SettingsFacts?>(null)

    /** The facts as last read, or null before the first read. */
    val facts: StateFlow<SettingsFacts?> = state.asStateFlow()

    /** Reads the facts from the vault again. It reads records, so it runs off the main thread except for a first read that has not happened. */
    fun refresh() {
        runCatching { readFacts(vault, vault.session) }.onSuccess { state.value = it }
    }
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
        finish = FinishSetupStore.read(session),
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
    lumioComposable(LumioRoute.SETTINGS_DISPLAY) {
        LumioBackdrop(selection) { DisplaySettingsRoute(navController = navController, holder = holder) }
    }
    lumioComposable(LumioRoute.SETTINGS_TRANSACTIONS) {
        LumioBackdrop(selection) { TransactionsSettingsRoute(navController = navController, holder = holder) }
    }
    lumioComposable(LumioRoute.SETTINGS_NETWORKS) {
        LumioBackdrop(selection) { NetworksSettingsRoute(navController = navController, holder = holder) }
    }
    lumioComposable(LumioRoute.SETTINGS_WALLETS) {
        LumioBackdrop(selection) {
            WalletsSettingsPage(
                onBack = { navController.popBackStack() },
                wallets = { WalletsSettingsRoute(navController = navController, holder = holder, listVersion = listVersion, onChanged = onChanged) },
            )
        }
    }
    lumioComposable(LumioRoute.SETTINGS_ADDRESSES) {
        LumioBackdrop(selection) {
            AddressBookSettingsRoute(navController = navController, holder = holder, listVersion = listVersion)
        }
    }
    lumioComposable(LumioRoute.SETTINGS_SECURITY) {
        LumioBackdrop(selection) { SecuritySettingsRoute(navController = navController, holder = holder, lockChoices = lockChoices) }
    }
    lumioComposable(LumioRoute.SETTINGS_ABOUT) {
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
 * change, Device Protection, whose row opens its page, and the lock. Each choice applies as the owner makes it.
 */
@Composable
private fun SecuritySettingsRoute(navController: NavHostController, holder: VaultHolder, lockChoices: LockSettingsState) {
    val session = holder.openSession() ?: return
    val biometric = (LocalContext.current.applicationContext as LumioApp).biometricUnlock
    val top by navController.currentBackStackEntryAsState()
    // Read again each time this page comes back to the top, because the Device Protection page may have turned the setting on or off.
    val biometricOn = remember(top) { biometric.isOn(session) }
    SecuritySettingsPage(
        entryGesture = LocalContext.current.entryGestureHolder().current,
        lock = lockChoices.settings,
        // A Duress Password session shows the row too, or its absence would tell that session apart. Turning it on there seals nothing.
        deviceProtectionOn = biometricOn,
        onEntryGesture = { navController.navigate(LumioRoute.ENTRY_GESTURE) },
        onChangePassword = { navController.navigate(LumioRoute.CHANGE_PASSWORD) },
        onDeviceProtection = { navController.navigate(LumioRoute.SETTINGS_DEVICE_PROTECTION) },
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
