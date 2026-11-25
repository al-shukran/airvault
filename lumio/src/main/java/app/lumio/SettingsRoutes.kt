package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import app.lensly.app.VaultHolder
import app.lensly.app.entryGestureHolder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.flow.checkAppPassword
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.config.DisplaySettingsStore
import app.lumio.data.config.TransactionSettingsStore
import app.lumio.data.config.FinishSetupStore
import app.lumio.evm.CustomChainInput
import app.lumio.evm.EvmSettingsStore
import app.lumio.pair.PairedWallets
import app.lumio.pair.sharedAddressBook
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.settings.AboutSettingsPage
import app.lumio.ui.settings.AddCustomNetworkScreen
import app.lumio.ui.settings.CustomNetworkForm
import app.lumio.ui.settings.LumioSettingsScreen
import app.lumio.ui.settings.NetworksSettingsPage
import app.lumio.ui.settings.BiometricSwitch
import app.lumio.ui.settings.SecuritySettingsPage
import app.lumio.ui.settings.SettingsFacts
import app.lumio.ui.settings.SettingsFrame
import app.lumio.ui.settings.WalletsSettingsPage
import app.lumio.ui.settings.page
import app.lumio.ui.settings.providerCounts
import app.lumio.ui.settings.settingsRows
import app.lumio.ui.shell.offlineBand
import app.lumio.ui.shell.rememberOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** The Settings tab's route: the root of the tab, with the list of pages in it. */
internal fun NavGraphBuilder.settingsRoute(
    navController: NavHostController,
    holder: VaultHolder,
    listVersion: Int,
    selection: NetworkSelection,
    lockChoices: LockSettingsState,
    onLock: () -> Unit,
) {
    lumioComposable(LumioRoute.SETTINGS) {
        LumioBackdrop(selection) {
            SettingsRoute(
                navController = navController,
                holder = holder,
                listVersion = listVersion,
                selection = selection,
                lockChoices = lockChoices,
                onLock = onLock,
            )
        }
    }
}

/**
 * The Settings tab: one row per page, each with its state under its title. The state comes from the open vault's [SettingsFactsHolder], read when the
 * vault opened, so the list is whole on its first frame. It is read again off the main thread each time the list comes to the top or the paired
 * wallets change, so a change made on a page shows here when the owner comes back to it.
 */
@Composable
private fun SettingsRoute(
    navController: NavHostController,
    holder: VaultHolder,
    listVersion: Int,
    selection: NetworkSelection,
    lockChoices: LockSettingsState,
    onLock: () -> Unit,
) {
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
    val scope = rememberCoroutineScope()
    TabRoute(tab = LumioTab.SETTINGS, navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = onLock) {
        LumioSettingsScreen(
            rows = settingsRows(current, gesture, BuildConfig.VERSION_NAME),
            finish = current.finish,
            onFinish = { item -> navController.navigate(LumioRoute.settingsPage(item.page)) },
            onOpen = { page -> navController.navigate(LumioRoute.settingsPage(page)) },
            onReset = {
                scope.launch {
                    // The erase runs to its end even if this screen leaves. A vault file that cannot be deleted leaves the erase unfinished, and the gate
                    // finishes it before it shows anything (see Vault.finishPendingWipe).
                    withContext(Dispatchers.IO + NonCancellable) { runCatching { app.eraseLumio() } }
                    lockChoices.reload()
                    navController.openHidden(GateRoutes.GATE)
                }
            },
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
        transactions = TransactionSettingsStore.read(session),
        chainsOn = EvmSettingsStore.read(session).enabled.size,
        customBitcoin = serverOverrides(configs).isNotEmpty(),
        providersOn = providersOn,
        providersTotal = providersTotal,
        wallets = wallets.size,
        addresses = sharedAddressBook(wallets).size,
        finish = FinishSetupStore.read(session),
    )
}

/**
 * The offline band of a settings page, as the shell draws it under the top bar: null while the device is online, and the time the balances were read
 * while it is not.
 */
@Composable
internal fun rememberSettingsOffline(): String? {
    val app = LocalContext.current.applicationContext as LumioApp
    return if (rememberOnline()) null else offlineBand(app.lumioVault()?.wallets?.updatedAtMs)
}

/** The routes of the pages below the list. Each one is a Hidden Area screen, with the testnet banner like every Lumio screen. */
internal fun NavGraphBuilder.settingsPageRoutes(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onChanged: () -> Unit,
    lockChoices: LockSettingsState,
    onLock: () -> Unit,
) {
    lumioComposable(LumioRoute.SETTINGS_DISPLAY) {
        LumioBackdrop(selection) { DisplaySettingsRoute(navController = navController, holder = holder, onLock = onLock) }
    }
    lumioComposable(LumioRoute.SETTINGS_TRANSACTIONS) {
        LumioBackdrop(selection) { TransactionsSettingsRoute(navController = navController, holder = holder, onLock = onLock) }
    }
    lumioComposable(LumioRoute.SETTINGS_NETWORKS) {
        LumioBackdrop(selection) { NetworksSettingsRoute(navController = navController, holder = holder, onLock = onLock) }
    }
    lumioComposable(LumioRoute.SETTINGS_BITCOIN) {
        LumioBackdrop(selection) { BitcoinServersRoute(navController = navController, holder = holder, onLock = onLock) }
    }
    lumioComposable(LumioRoute.SETTINGS_ADD_NETWORK) {
        LumioBackdrop(selection) { AddCustomNetworkRoute(navController = navController, holder = holder, onLock = onLock) }
    }
    lumioComposable(LumioRoute.SETTINGS_WALLETS) {
        LumioBackdrop(selection) {
            WalletsSettingsPage(
                offline = rememberSettingsOffline(),
                onBack = { navController.popBackStack() },
                onLock = onLock,
                wallets = { WalletsSettingsRoute(navController = navController, holder = holder, selection = selection, listVersion = listVersion) },
            )
        }
    }
    lumioComposable(
        route = LumioRoute.SETTINGS_WALLET,
        arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
    ) { entry ->
        val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
        LumioBackdrop(selection) {
            WalletSettingsRoute(navController = navController, holder = holder, walletId = walletId, listVersion = listVersion, onChanged = onChanged, onLock = onLock)
        }
    }
    lumioComposable(LumioRoute.SETTINGS_ADDRESSES) {
        LumioBackdrop(selection) {
            AddressBookSettingsRoute(navController = navController, holder = holder, listVersion = listVersion, onLock = onLock)
        }
    }
    lumioComposable(LumioRoute.SETTINGS_SECURITY) {
        LumioBackdrop(selection) { SecuritySettingsRoute(navController = navController, holder = holder, lockChoices = lockChoices, onLock = onLock) }
    }
    lumioComposable(LumioRoute.SETTINGS_ABOUT) {
        LumioBackdrop(selection) { AboutSettingsRoute(navController = navController, onLock = onLock) }
    }
}

/** The Networks page: the EVM networks, with the way to the Bitcoin servers, read and written by [EvmSettingsRoute]. */
@Composable
private fun NetworksSettingsRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    NetworksSettingsPage(
        offline = rememberSettingsOffline(),
        onBack = { navController.popBackStack() },
        onLock = onLock,
        backLabel = navController.backLabel(),
    ) {
        EvmSettingsRoute(navController = navController, holder = holder)
    }
}

/** The Bitcoin servers page, pushed from the Networks page. [BitcoinSettingsRoute] reads and writes its fields. */
@Composable
private fun BitcoinServersRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    SettingsFrame(
        title = "Bitcoin Servers",
        offline = rememberSettingsOffline(),
        onBack = { navController.popBackStack() },
        onLock = onLock,
        backLabel = "Networks",
    ) {
        BitcoinSettingsRoute(navController = navController, holder = holder)
    }
}

/**
 * The Add Custom Network page. The form is held here, and Add Network checks the endpoint off the main thread. A saved network goes back to the
 * Networks page, which reads the chains again when it is shown; a refusal stays on the page under the field it concerns.
 */
@Composable
private fun AddCustomNetworkRoute(navController: NavHostController, holder: VaultHolder, onLock: () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(CustomNetworkForm(CustomChainInput(chainId = "", name = "", symbol = "", rpc = "", testnet = false))) }
    var busy by remember { mutableStateOf(false) }
    AddCustomNetworkScreen(
        input = form.input,
        refusal = form.refusal,
        busy = busy,
        offline = rememberSettingsOffline(),
        backLabel = navController.backLabel(),
        onInput = { form = form.edited(it) },
        onAdd = {
            busy = true
            scope.launch {
                val builtIn = app.registry.networks().map { it.chainId }.toSet()
                val result = withContext(Dispatchers.IO) { EvmSettingsStore.addCustom(session, app.evmRpc, builtIn, form.input) }
                busy = false
                if (result == null) navController.popBackStack() else form = form.copy(refusal = result)
            }
        },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}

/**
 * The Security page: the App Password, whose row opens its change, Device Protection, whose switch turns Biometric Unlock off, or on through the App
 * Password step, the Hidden Entry Gesture, whose row opens the choice shared through :shared, and the lock. Each choice applies as the owner makes it.
 * In a Decoy Vault session the switch shows too, or its absence would tell that session apart, and turning it on there seals nothing.
 */
@Composable
private fun SecuritySettingsRoute(navController: NavHostController, holder: VaultHolder, lockChoices: LockSettingsState, onLock: () -> Unit) {
    val session = holder.openSession() ?: return
    val biometric = (LocalContext.current.applicationContext as LumioApp).biometricUnlock
    val top by navController.currentBackStackEntryAsState()
    val scope = rememberCoroutineScope()
    val canEnable = remember { biometric.canEnable() }
    // Read again each time this page comes back to the top, because the App Password step may have turned the setting on.
    var on by remember(top) { mutableStateOf(biometric.isOn(session)) }
    SecuritySettingsPage(
        lock = lockChoices.settings,
        biometric = BiometricSwitch(on = on, canEnable = canEnable),
        offline = rememberSettingsOffline(),
        onChangePassword = { navController.navigate(LumioRoute.CHANGE_PASSWORD) },
        onBiometric = { want ->
            if (want) {
                if (turningOnOpensPasswordStep(session)) navController.navigate(LumioRoute.DEVICE_PROTECTION_ENABLE)
            } else {
                scope.launch {
                    biometric.disable(session)
                    on = biometric.isOn(session)
                }
            }
        },
        onEntryGesture = { navController.navigate(LumioRoute.ENTRY_GESTURE) },
        checkPassword = { password -> checkAppPassword(holder, password) },
        onAutoLock = { choice -> lockChoices.change { it.copy(autoLock = choice) } },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}

/** The About page: the version of this build, and the licences it ships under. */
@Composable
private fun AboutSettingsRoute(navController: NavHostController, onLock: () -> Unit) {
    AboutSettingsPage(
        version = BuildConfig.VERSION_NAME,
        offline = rememberSettingsOffline(),
        onLicences = { navController.navigate(LumioRoute.LICENCES) },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}
