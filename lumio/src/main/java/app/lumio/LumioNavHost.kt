package app.lumio

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.airvault.core.model.AccountId
import app.lensly.app.export.PairingPayload
import app.lensly.app.vaultHolder
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.navigation.CoverNavHost
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.MirrorNavMotion
import app.lensly.ui.navigation.hiddenArea
import app.lensly.ui.navigation.leaveHidden
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.WallClock
import app.lumio.data.config.Fiat
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.FiatRate
import app.lumio.data.config.LockSettings
import app.lumio.data.config.LockSettingsStore
import app.lumio.data.config.ThemeMode
import app.lumio.data.config.fiatRateOf
import app.lumio.network.NetworkMode
import app.lumio.network.NetworkModeStore
import app.lumio.send.algorand.AlgorandAddAssetRoute
import app.lumio.send.algorand.AlgorandSendRoute
import app.lumio.send.solana.SolanaSendRoute
import app.lumio.ui.firstrun.FirstRunStep
import app.lumio.ui.gate.ClearAirGateLook
import app.lumio.ui.kit.ClearAirTestnetHatch
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.settings.SettingsPage
import app.lumio.ui.wallets.NATIVE_ASSET_KEY
import app.lumio.watchlist.WatchedAsset
import kotlinx.coroutines.launch


/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    /** The Accounts tab: the paired wallets. */
    const val WALLETS = "wallets"
    const val SETTINGS = "settings"
    const val PAIR_SCAN = "pair-scan"
    const val PAIR_REVIEW = "pair-review"
    const val DATA_PROVIDERS = "settings/providers"
    const val LICENCES = "settings/licences"

    /** The Provider page of one provider, by its catalogue ID. */
    const val PROVIDER = "settings/providers/{provider}"
    const val PROVIDER_ARG = "provider"

    fun provider(id: String): String = "settings/providers/$id"

    /** The roots of the tabs, which the tab bar opens. Home is the start screen. */
    const val HOME = "home"
    const val PORTFOLIO = "portfolio"
    const val EXPLORE = "explore"

    /** The Add to Watchlist page, pushed from Home's "+ Add" and its dashed add row. */
    const val WATCHLIST_ADD = "watchlist/add"

    /** The activity of every account on the side, pushed from the Portfolio tab and its "All" link. */
    const val ACTIVITY = "activity"

    /** The route of the activity of [asset] on every account of the side, pushed from the Asset page's "All" link. */
    fun activity(asset: WatchedAsset): String = "activity/${coinSegments(asset)}"

    /** The five tab roots, in the order of the tab bar. A screen in this set is a place of the tab bar, not a page pushed over one. */
    val TAB_ROOTS = setOf(HOME, WALLETS, PORTFOLIO, EXPLORE, SETTINGS)

    /** The pages of the settings list below the list: Display, Transactions, Networks, Wallets, the address books, Security, About and Developer. */
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_TRANSACTIONS = "settings/transactions"
    const val SETTINGS_NETWORKS = "settings/networks"

    /** The Bitcoin servers page, opened from the Networks page: the Esplora server of each Bitcoin network, and the Ordinals indexer. */
    const val SETTINGS_BITCOIN = "settings/networks/bitcoin"

    /** The Add Custom Network page, opened from the Networks page and from the Test Networks page. */
    const val SETTINGS_ADD_NETWORK = "settings/networks/add"
    const val SETTINGS_WALLETS = "settings/wallets"
    const val SETTINGS_ADDRESSES = "settings/addresses"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_ABOUT = "settings/about"

    /** The App Password change, opened from the Security page. */
    const val CHANGE_PASSWORD = "settings/security/password"

    /** The Duress Password set, opened from the Security page. */
    const val DURESS_PASSWORD = "settings/security/duress"

    /** The App Password step that turning Biometric Unlock on from the Security page asks for first. */
    const val DEVICE_PROTECTION_ENABLE = "settings/security/device-protection/enable"

    /** The route of the settings page [page]. Data providers keeps its own route, as its screen does. */
    fun settingsPage(page: SettingsPage): String = when (page) {
        SettingsPage.DISPLAY -> SETTINGS_DISPLAY
        SettingsPage.TRANSACTIONS -> SETTINGS_TRANSACTIONS
        SettingsPage.NETWORKS -> SETTINGS_NETWORKS
        SettingsPage.PROVIDERS -> DATA_PROVIDERS
        SettingsPage.WALLETS -> SETTINGS_WALLETS
        SettingsPage.ADDRESS_BOOK -> SETTINGS_ADDRESSES
        SettingsPage.SECURITY -> SETTINGS_SECURITY
        SettingsPage.ABOUT -> SETTINGS_ABOUT
    }

    /** The argument of the tokens route: the chain ID whose tokens the screen lists. */
    const val CHAIN_ARG = "chainId"

    /** The tokens of one chain: its bundled defaults, and the owner's own tokens with the add form. */
    const val TOKENS = "settings/tokens/{$CHAIN_ARG}"

    /** The route of the tokens of chain [chainId]. */
    fun tokens(chainId: Long): String = "settings/tokens/$chainId"

    /** The Network page (B4) of chain [chainId]: its default and custom RPC address. */
    const val NETWORK = "settings/chains/{$CHAIN_ARG}"

    /** The route of the Network page of chain [chainId]. */
    fun network(chainId: Long): String = "settings/chains/$chainId"

    /** The argument of the gesture change: the gesture's stored number. */
    const val GESTURE_ARG = "gesture"

    /** The Hidden Entry Gesture's choice, and the change to the gesture named by [GESTURE_ARG]. Both are shared with Lensly's screens. */
    const val ENTRY_GESTURE = "settings/gesture"
    const val ENTRY_GESTURE_CHANGE = "settings/gesture/change/{$GESTURE_ARG}"

    /** The route of the change to [gesture], which takes its stored number as [GESTURE_ARG]. */
    fun entryGestureChange(gesture: EntryGesture): String = "settings/gesture/change/${gesture.storedId}"

    /** The argument of a paired wallet's routes: the wallet's ID. */
    const val WALLET_ARG = "walletId"

    /** The first sync of the wallet named by [WALLET_ARG], opened when its pairing is saved. */
    const val FIRST_SYNC = "first-sync/{$WALLET_ARG}"

    /** The route of the first sync of [walletId]. */
    fun firstSync(walletId: String): String = "first-sync/$walletId"

    /** The argument of an account's route: the account's CAIP-10 id, as [AccountId.caip10]. */
    const val ACCOUNT_ARG = "account"

    /** The argument of an asset's route: the asset's key among the account's assets, as [AssetRows] keys them. */
    const val ASSET_KEY_ARG = "assetKey"

    /** One asset of an account, with the account's wallet and id, and the asset's key, as its arguments. */
    const val ASSET = "asset/{$WALLET_ARG}/{$ACCOUNT_ARG}/{$ASSET_KEY_ARG}"

    /** The route of the asset [assetKey] of [account] in [walletId]. */
    fun asset(walletId: String, account: AccountId, assetKey: String): String = "asset/$walletId/${account.caip10}/$assetKey"

    /** The arguments of a coin's routes: the wallet, the coin's chain and its testnet flag, and its contract or the native key. */
    const val COIN_CHAIN_ARG = "chain"
    const val COIN_TESTNET_ARG = "testnet"
    const val COIN_CONTRACT_ARG = "contract"

    /** The activity of one coin on every account of the side, pushed from the Asset page's "All" link. It takes the coin's arguments. */
    const val ACTIVITY_COIN = "activity/{$COIN_CHAIN_ARG}/{$COIN_TESTNET_ARG}/{$COIN_CONTRACT_ARG}"

    /** The Asset page (C4) of a coin across one wallet, opened from Home, Search and the watchlist. */
    const val COIN = "coin/{$WALLET_ARG}/{$COIN_CHAIN_ARG}/{$COIN_TESTNET_ARG}/{$COIN_CONTRACT_ARG}"

    /** The route of the Asset page of [asset] across the wallet [walletId]. */
    fun coin(walletId: String, asset: WatchedAsset): String = "coin/$walletId/${coinSegments(asset)}"

    /** The Holdings Breakdown (C5) of [asset] in the wallet [walletId], pushed from the Asset page. */
    const val HOLDINGS = "holdings/{$WALLET_ARG}/{$COIN_CHAIN_ARG}/{$COIN_TESTNET_ARG}/{$COIN_CONTRACT_ARG}"

    /** The route of the Holdings Breakdown of [asset] in the wallet [walletId]. */
    fun holdings(walletId: String, asset: WatchedAsset): String = "holdings/$walletId/${coinSegments(asset)}"

    private fun coinSegments(asset: WatchedAsset): String = "${asset.network.chain.caip2}/${asset.network.testnet}/${asset.contract ?: NATIVE_ASSET_KEY}"

    /** One paired wallet's page, opened from the Accounts tab and from the profile panel. */
    const val WALLET = "wallet/{$WALLET_ARG}"

    /** The route of the page of the wallet [walletId]. */
    fun wallet(walletId: String): String = "wallet/$walletId"

    /** One account's page, of the wallet in [WALLET_ARG], opened from its wallet's page. */
    const val ACCOUNT = "account/{$WALLET_ARG}/{$ACCOUNT_ARG}"

    /** The route of the page of [account] in [walletId]. */
    fun account(walletId: String, account: AccountId): String = "account/$walletId/${account.caip10}"

    /** The test networks page, opened from the profile panel. */
    const val TEST_NETWORKS = "test-networks"

    /** The route of the read-only Address Book of [walletId], opened from the wallet's row in Settings. */
    const val ADDRESS_BOOK = "settings/address-book/{$WALLET_ARG}"

    fun addressBook(walletId: String): String = "settings/address-book/$walletId"

    /** The route of the settings of one paired wallet, opened from the Wallets page. */
    const val SETTINGS_WALLET = "settings/wallets/{$WALLET_ARG}"

    fun settingsWallet(walletId: String): String = "settings/wallets/$walletId"

    /** The argument of the send's route: the chain ID of the network the send is on. */
    const val SEND_CHAIN_ARG = "chain"

    /** The argument of the send's route: the contract of the token the send moves. Left out, the send moves the native coin of the chain. */
    const val SEND_CONTRACT_ARG = "contract"

    /** The argument of the send's route: when true, the send opens with the camera on for its recipient, as Scan to Pay does. */
    const val SEND_SCAN_ARG = "scan"

    /**
     * The Ethereum send from one account of the wallet in [WALLET_ARG], on the network [SEND_CHAIN_ARG], of the token [SEND_CONTRACT_ARG] when set. With
     * [SEND_SCAN_ARG] set, it opens on the recipient's camera.
     */
    const val SEND = "send/{$WALLET_ARG}/{$ACCOUNT_ARG}?$SEND_CHAIN_ARG={$SEND_CHAIN_ARG}&$SEND_CONTRACT_ARG={$SEND_CONTRACT_ARG}&$SEND_SCAN_ARG={$SEND_SCAN_ARG}"

    /** The Solana send from the account [ACCOUNT_ARG] of the wallet in [WALLET_ARG]. Its side (testnet or mainnet) is the selected side. */
    const val SOLANA_SEND = "solana-send/{$WALLET_ARG}/{$ACCOUNT_ARG}"

    /** The route of the Solana send from [account] of [walletId]. */
    fun solanaSend(walletId: String, account: AccountId): String = "solana-send/$walletId/${account.caip10}"

    /** The Algorand send from the account [ACCOUNT_ARG] of the wallet in [WALLET_ARG]. Its side (testnet or mainnet) is the selected side. */
    const val ALGORAND_SEND = "algorand-send/{$WALLET_ARG}/{$ACCOUNT_ARG}"

    /** The route of the Algorand send from [account] of [walletId]. */
    fun algorandSend(walletId: String, account: AccountId): String = "algorand-send/$walletId/${account.caip10}"

    /** The Add asset screen of the Algorand account [ACCOUNT_ARG] of the wallet in [WALLET_ARG]. */
    const val ALGORAND_ADD_ASSET = "algorand-add-asset/{$WALLET_ARG}/{$ACCOUNT_ARG}"

    /** The route of the Add asset screen of the Algorand [account] of [walletId]. */
    fun algorandAddAsset(walletId: String, account: AccountId): String = "algorand-add-asset/$walletId/${account.caip10}"

    /** The argument of an asset: the ID of the Algorand asset that an opt-in adds to the account. */
    const val ASSET_ARG = "asset"

    /** The opt-in of the Algorand asset in [ASSET_ARG] to the account [ACCOUNT_ARG] of the wallet in [WALLET_ARG]. */
    const val ALGORAND_OPT_IN = "algorand-opt-in/{$WALLET_ARG}/{$ACCOUNT_ARG}/{$ASSET_ARG}"

    /** The route of the opt-in of asset [assetId] to the Algorand [account] of [walletId]. */
    fun algorandOptIn(walletId: String, account: AccountId, assetId: ULong): String = "algorand-opt-in/$walletId/${account.caip10}/$assetId"

    /** The Bitcoin send from the account [ACCOUNT_ARG] of the wallet in [WALLET_ARG]. Its side (testnet4 or mainnet) is the selected side. */
    const val BTC_SEND = "bitcoin-send/{$WALLET_ARG}/{$ACCOUNT_ARG}"

    /** The route of the Bitcoin send from [account] of [walletId]. */
    fun btcSend(walletId: String, account: AccountId): String = "bitcoin-send/$walletId/${account.caip10}"

    /**
     * The route of a send from [account] of [walletId] on the network [chainId], of the token at [contract] when one is given. [scan] opens it with the
     * camera on for the recipient.
     */
    fun send(walletId: String, account: AccountId, chainId: Long, contract: String? = null, scan: Boolean = false): String =
        "send/$walletId/${account.caip10}?$SEND_CHAIN_ARG=$chainId" +
            contract?.let { "&$SEND_CONTRACT_ARG=$it" }.orEmpty() +
            if (scan) "&$SEND_SCAN_ARG=true" else ""

    /** The arguments of a transaction's page: the account that the row is for, and the transaction's id. */
    const val TX_ACCOUNT_ARG = "account"
    const val TX_ID_ARG = "txId"

    /** A transaction's page, for the row of [TX_ACCOUNT_ARG] and [TX_ID_ARG] in the wallet [WALLET_ARG], whose Address Book the addresses are named in. */
    const val TRANSACTION = "transaction/{$WALLET_ARG}/{$TX_ACCOUNT_ARG}/{$TX_ID_ARG}"

    /** The arguments of an address's handoff page: the address, and whether it updates an entry that is already in the address book. */
    const val ADDRESS_ARG = "address"
    const val EDIT_ARG = "edit"

    /** The handoff page that shows an address as a code for Lensly to read, to add it or to edit it. */
    const val ADDRESS_HANDOFF = "address-handoff/{$ADDRESS_ARG}/{$EDIT_ARG}"

    /** The route of the handoff page of [address], which edits its entry when [edit] is set. */
    fun addressHandoff(address: String, edit: Boolean): String = "address-handoff/$address/$edit"

    /** The route of the page of transaction [txId] for [account], an account of the wallet [walletId]. */
    fun transaction(walletId: String, account: String, txId: String): String = "transaction/$walletId/$account/$txId"
}

/** The account that a route names by its CAIP-10 id, as [LumioRoute.ACCOUNT_ARG]. Our routes always carry one. */
private fun NavBackStackEntry.accountId(): AccountId = AccountId(arguments?.getString(LumioRoute.ACCOUNT_ARG).orEmpty())

/** The chain argument of a send that names no chain. Chain IDs are never zero. */
private const val NO_CHAIN = 0L

/**
 * The pairing that a scan has read and the owner has not saved yet. It holds public data only, and it is dropped on save, on cancel and on
 * lock.
 */
internal class PairDraft {
    var pairing by mutableStateOf<PairingPayload?>(null)

    fun clear() {
        pairing = null
    }
}

/**
 * The choices of the shell: the side of the networks the owner chose, and the wallet that the profile panel chose for Home and Portfolio. The wallet
 * screens read the side, so the testnet strip shows on every Lumio screen while the side is testnet. [choose] applies a side at once, and passes it to
 * [store], which keeps it in the vault. [walletId] is null until the owner chooses a wallet, and the first paired wallet is the active one until then.
 */
internal class NetworkSelection(private val store: (NetworkMode) -> Unit = {}) {
    var mode by mutableStateOf(NetworkMode.MAINNET)
    var walletId by mutableStateOf<String?>(null)

    /** Applies [next] at once, and keeps it. */
    fun choose(next: NetworkMode) {
        mode = next
        store(next)
    }
}

/**
 * The lock choices of Settings → Security, as observable state, so the Security page and the cover read a change at once. Each change is
 * saved as it is made.
 */
internal class LockSettingsState(private val store: LockSettingsStore) {
    var settings: LockSettings by mutableStateOf(store.load())
        private set

    /** Applies [transform] to the choices, and saves them. */
    fun change(transform: (LockSettings) -> LockSettings) {
        val next = transform(settings)
        store.save(next)
        settings = next
    }

    /** Reads the choices again, after a reset erased them with the rest of Lumio. */
    fun reload() {
        settings = store.load()
    }
}

/**
 * A Lumio screen in the Hidden Area: Clear Air over the shared backdrop, with the testnet band under the status bar while the side is testnet, or
 * while [testSide] says the screen is a test-side page. The gate screens are not Lumio's, so they keep the plain backdrop in Lensly's theme.
 *
 * Lumen's locals stay in scope under Clear Air until the screens that still read them are restyled, so Clear Air's theme wraps them rather than
 * replacing Lumen's providers.
 */
@Composable
internal fun LumioBackdrop(
    selection: NetworkSelection,
    testSide: Boolean = selection.mode == NetworkMode.TESTNET,
    content: @Composable () -> Unit,
) {
    LumenTheme {
        ClearAirTheme {
            MirrorNavMotion()
            HiddenBackdrop {
                Column(modifier = Modifier.fillMaxSize()) {
                    // The band takes the status bar's height, and the hatch sits under it. The screen below consumes the same inset, so its own
                    // top bar meets the band with no second gap.
                    Box(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                        if (testSide) ClearAirTestnetHatch(text = "TESTNET · TEST FUNDS HAVE NO VALUE")
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) { content() }
                }
            }
        }
    }
}

/**
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the gate and
 * then shows the Accounts tab. The five tabs (Home, Accounts, Portfolio, Explore and Settings) are the roots, and every other place is a page pushed
 * over its root: a wallet, an account, Send and Receive, the test networks and the settings pages. The profile panel and the search sheet open from
 * a tab's top bar, and at most one sheet is ever open.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier, navController: NavHostController = rememberNavController()) {
    ThemeProvider(navController) {
        FiatProvider(navController) {
            LumioNavGraph(debugHiddenRequest = debugHiddenRequest, modifier = modifier, controller = navController)
        }
    }
}

/**
 * Gives every screen below it the theme the owner chose on the Display page. A chosen Light or Dark replaces the phone's night setting in the configuration
 * that the themes read, so each of them, and the sheets and the status bar icons, follow it. Follow System leaves the configuration as the phone has it, and so
 * does a locked vault, which has no choice to read.
 */
@Composable
private fun ThemeProvider(navController: NavHostController, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    // The vault opens after the first composition, so the choice is read again on each navigation, as the currency is.
    navController.currentBackStackEntryAsState().value
    val theme = rememberDisplay(app).theme
    val phone = LocalConfiguration.current
    val configuration = remember(phone, theme) {
        val night = when (theme) {
            ThemeMode.SYSTEM -> return@remember phone
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        Configuration(phone).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
    }
    CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
}

/**
 * Provides [LocalFiat]: the owner's currency with its rate. The rate is read again each time the owner opens a screen, and it is cache-first, so a screen
 * never waits for a source. US dollars while the vault is locked, in US dollars, or while no rate is known.
 */
@Composable
private fun FiatProvider(navController: NavHostController, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as LumioApp
    val currency = rememberDisplay(app).currency
    val entry by navController.currentBackStackEntryAsState()
    val rate by produceState<FiatRate?>(initialValue = null, currency, entry) {
        value = if (currency == FiatCurrency.USD) null else app.prices.fiatRates()?.value?.let { fiatRateOf(it, currency, WallClock.nowMs()) }
    }
    CompositionLocalProvider(LocalFiat provides Fiat.of(currency, rate), content = content)
}

@Composable
private fun LumioNavGraph(debugHiddenRequest: Int, modifier: Modifier, controller: NavHostController) {
    val app = LocalContext.current.applicationContext as LumioApp
    val draft = remember { PairDraft().also { it.pairing = DebugDemoData.pairing() } }
    // The side is kept in the vault when the owner picks it, as Settings kept it, so the next open shows the same side.
    val appHolder = LocalContext.current.vaultHolder()
    val scope = rememberCoroutineScope()
    val selection = remember {
        NetworkSelection(store = { next ->
            scope.launch {
                onVault(Unit) {
                    val session = appHolder.openSession()
                    if (session != null) NetworkModeStore.write(session, next)
                }
            }
        })
    }
    val lockChoices = remember { LockSettingsState(app.lockSettings) }
    DebugDemoData.Entry(controller, selection, debugHiddenRequest)
    // Coming back from the background reads the networks again once, when the Wallets home shows. The mark waits for the home, so no pushed screen reads.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) app.lumioVault()?.wallets?.markRefreshDue()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Counts the saves and deletions, so Home and Settings read the paired wallets again.
    var listVersion by remember { mutableIntStateOf(0) }
    CoverNavHost(
        debugHiddenRequest = debugHiddenRequest,
        modifier = modifier,
        navController = controller,
        // The top bar's lock icon clears the pairing draft too, as the Home lock does.
        lockVault = { navController, holder ->
            draft.clear()
            leaveHidden(navController, holder)
        },
        lockPolicy = lockChoices.settings.policy,
    ) { navController, holder ->
        fun lock() {
            draft.clear()
            leaveHidden(navController, holder)
        }
        hiddenArea(
            navController,
            holder,
            home = LumioRoute.HOME,
            appName = "Lumio",
            newVault = FirstRunStep.Welcome.route,
            biometric = app.biometricUnlock,
            gateLook = ClearAirGateLook,
        ) {
            firstRunRoutes(navController, holder, home = LumioRoute.HOME)
            lumioComposable(LumioRoute.WALLETS) {
                LumioBackdrop(selection) {
                    WalletsRoute(
                        navController = navController,
                        holder = holder,
                        selection = selection,
                        listVersion = listVersion,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(LumioRoute.HOME) {
                // The Home tab is the start screen and has no back arrow. System back locks the vault first, so leaving the app never
                // leaves the vault open, then closes the app as Android does at a root screen.
                val activity = LocalActivity.current
                BackHandler {
                    lock()
                    activity?.finish()
                }
                LumioBackdrop(selection) {
                    HomeRoute(navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = { lock() })
                }
            }
            lumioComposable(LumioRoute.PORTFOLIO) {
                LumioBackdrop(selection) {
                    PortfolioTab(navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = { lock() })
                }
            }
            lumioComposable(LumioRoute.EXPLORE) {
                LumioBackdrop(selection) {
                    ExploreRoute(navController = navController, holder = holder, selection = selection, listVersion = listVersion, onLock = { lock() })
                }
            }
            lumioComposable(
                route = LumioRoute.WALLET,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    WalletRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        selection = selection,
                        listVersion = listVersion,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.ACCOUNT,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                LumioBackdrop(selection) {
                    AccountRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        accountId = account,
                        selection = selection,
                        listVersion = listVersion,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(route = LumioRoute.COIN, arguments = coinArguments()) { entry ->
                LumioBackdrop(selection) {
                    AssetRoute(
                        navController = navController,
                        holder = holder,
                        selection = selection,
                        listVersion = listVersion,
                        walletId = entry.coinWallet(),
                        asset = entry.coinAsset(),
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(route = LumioRoute.HOLDINGS, arguments = coinArguments()) { entry ->
                LumioBackdrop(selection) {
                    HoldingsRoute(
                        navController = navController,
                        holder = holder,
                        selection = selection,
                        listVersion = listVersion,
                        walletId = entry.coinWallet(),
                        asset = entry.coinAsset(),
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(LumioRoute.TEST_NETWORKS) {
                LumioBackdrop(selection, testSide = true) {
                    TestNetworksRoute(navController = navController, holder = holder, onLock = { lock() })
                }
            }
            lumioComposable(
                route = LumioRoute.TRANSACTION,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.TX_ACCOUNT_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.TX_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.arguments?.getString(LumioRoute.TX_ACCOUNT_ARG).orEmpty()
                val txId = entry.arguments?.getString(LumioRoute.TX_ID_ARG).orEmpty()
                LumioBackdrop(selection) {
                    TransactionDetailRoute(navController = navController, holder = holder, walletId = walletId, account = account, txId = txId, onLock = { lock() })
                }
            }
            lumioComposable(LumioRoute.ACTIVITY) {
                LumioBackdrop(selection) {
                    ActivityRoute(navController = navController, holder = holder, selection = selection, listVersion = listVersion, coin = null, onLock = { lock() })
                }
            }
            lumioComposable(route = LumioRoute.ACTIVITY_COIN, arguments = coinAssetArguments()) { entry ->
                LumioBackdrop(selection) {
                    ActivityRoute(
                        navController = navController,
                        holder = holder,
                        selection = selection,
                        listVersion = listVersion,
                        coin = entry.coinAsset(),
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.ADDRESS_HANDOFF,
                arguments = listOf(
                    navArgument(LumioRoute.ADDRESS_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.EDIT_ARG) { type = NavType.BoolType },
                ),
            ) { entry ->
                val address = entry.arguments?.getString(LumioRoute.ADDRESS_ARG).orEmpty()
                val edit = entry.arguments?.getBoolean(LumioRoute.EDIT_ARG) ?: false
                LumioBackdrop(selection) {
                    AddOnLenslyRoute(navController = navController, address = address, edit = edit, onLock = { lock() })
                }
            }
            lumioComposable(LumioRoute.PAIR_SCAN) {
                LumioBackdrop(selection) { PairScanRoute(navController = navController, draft = draft) }
            }
            lumioComposable(
                route = LumioRoute.BTC_SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                LumioBackdrop(selection) {
                    BtcSendRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        account = account,
                        mode = selection.mode,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.SOLANA_SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                LumioBackdrop(selection) {
                    SolanaSendRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        accountId = account,
                        mode = selection.mode,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.ALGORAND_SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                LumioBackdrop(selection) {
                    AlgorandSendRoute(
                        navController = navController,
                        holder = holder,
                        mode = selection.mode,
                        walletId = walletId,
                        account = account,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.ALGORAND_ADD_ASSET,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                LumioBackdrop(selection) {
                    AlgorandAddAssetRoute(navController = navController, holder = holder, mode = selection.mode, walletId = walletId, account = account)
                }
            }
            lumioComposable(
                route = LumioRoute.ALGORAND_OPT_IN,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ASSET_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                val assetId = entry.arguments?.getString(LumioRoute.ASSET_ARG)?.toULongOrNull()
                LumioBackdrop(selection) {
                    AlgorandSendRoute(
                        navController = navController,
                        holder = holder,
                        mode = selection.mode,
                        walletId = walletId,
                        account = account,
                        onLock = { lock() },
                        optInAssetId = assetId,
                    )
                }
            }
            settingsRoute(
                navController = navController,
                holder = holder,
                listVersion = listVersion,
                selection = selection,
                lockChoices = lockChoices,
                onLock = { lock() },
            )
            settingsPageRoutes(
                navController = navController,
                holder = holder,
                selection = selection,
                listVersion = listVersion,
                onChanged = { listVersion++ },
                lockChoices = lockChoices,
                onLock = { lock() },
            )
            entryGestureRoutes(navController = navController, holder = holder, selection = selection)
            changePasswordRoutes(navController = navController, holder = holder, selection = selection)
            duressPasswordRoutes(navController = navController, holder = holder, selection = selection)
            deviceProtectionRoutes(navController = navController, holder = holder, selection = selection)
            lumioComposable(LumioRoute.DATA_PROVIDERS) {
                LumioBackdrop(selection) { DataProvidersRoute(navController = navController, holder = holder, onLock = { lock() }) }
            }
            lumioComposable(
                route = LumioRoute.PROVIDER,
                arguments = listOf(navArgument(LumioRoute.PROVIDER_ARG) { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString(LumioRoute.PROVIDER_ARG).orEmpty()
                LumioBackdrop(selection) { ProviderRoute(navController = navController, holder = holder, id = id, onLock = { lock() }) }
            }
            lumioComposable(LumioRoute.LICENCES) {
                LumioBackdrop(selection) { LicencesRoute(navController = navController) }
            }
            lumioComposable(
                route = LumioRoute.TOKENS,
                arguments = listOf(navArgument(LumioRoute.CHAIN_ARG) { type = NavType.LongType }),
            ) { entry ->
                val chainId = entry.arguments?.getLong(LumioRoute.CHAIN_ARG) ?: 0L
                LumioBackdrop(selection) {
                    AddTokenRoute(navController = navController, holder = holder, chainId = chainId, selection = selection, onLock = { lock() })
                }
            }
            lumioComposable(
                route = LumioRoute.NETWORK,
                arguments = listOf(navArgument(LumioRoute.CHAIN_ARG) { type = NavType.LongType }),
            ) { entry ->
                val chainId = entry.arguments?.getLong(LumioRoute.CHAIN_ARG) ?: 0L
                LumioBackdrop(selection) { EvmNetworkRoute(navController = navController, holder = holder, chainId = chainId, onLock = { lock() }) }
            }
            lumioComposable(
                route = LumioRoute.ADDRESS_BOOK,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) { AddressBookRoute(navController = navController, holder = holder, walletId = walletId, onLock = { lock() }) }
            }
            lumioComposable(
                route = LumioRoute.ASSET,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ASSET_KEY_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                val assetKey = entry.arguments?.getString(LumioRoute.ASSET_KEY_ARG).orEmpty()
                LumioBackdrop(selection) {
                    AssetRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        accountId = account,
                        assetKey = assetKey,
                        mode = selection.mode,
                        listVersion = listVersion,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_CHAIN_ARG) { type = NavType.LongType; defaultValue = NO_CHAIN },
                    navArgument(LumioRoute.SEND_CONTRACT_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(LumioRoute.SEND_SCAN_ARG) { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val account = entry.accountId()
                val chain = entry.arguments?.getLong(LumioRoute.SEND_CHAIN_ARG) ?: NO_CHAIN
                val contract = entry.arguments?.getString(LumioRoute.SEND_CONTRACT_ARG)
                val scanFirst = entry.arguments?.getBoolean(LumioRoute.SEND_SCAN_ARG) == true
                LumioBackdrop(selection) {
                    SendRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        account = account,
                        mode = selection.mode,
                        chainId = chain,
                        scanFirst = scanFirst,
                        initialContract = contract,
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(LumioRoute.PAIR_REVIEW) {
                BackHandler {
                    draft.clear()
                    navController.backToAccounts()
                }
                LumioBackdrop(selection) {
                    PairReviewRoute(
                        navController = navController,
                        holder = holder,
                        draft = draft,
                        onChanged = { listVersion++ },
                        onLock = { lock() },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.FIRST_SYNC,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    FirstSyncRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        mode = selection.mode,
                        onLock = { lock() },
                    )
                }
            }
        }
    }
}
