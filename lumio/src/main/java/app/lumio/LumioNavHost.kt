package app.lumio

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
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.lensly.app.export.PairingPayload
import app.lensly.app.vaultHolder
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.navigation.CoverNavHost
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.hiddenArea
import app.lensly.ui.navigation.leaveHidden
import app.lumio.data.config.LockSettings
import app.lumio.data.config.LockSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.send.algorand.AlgorandAddAssetRoute
import app.lumio.send.algorand.AlgorandSendRoute
import app.lumio.send.solana.SolanaSendRoute
import app.lumio.network.NetworkModeStore
import app.lumio.ui.kit.LocalSharedScope
import app.lensly.ui.navigation.MirrorNavMotion
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.network.NetworkSideSheet
import app.lumio.ui.network.TestnetStrip
import app.lumio.ui.settings.SettingsPage
import kotlinx.coroutines.launch
import app.lensly.ui.theme.LumenTheme
import app.lumio.wallets.AccountChain

/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    /** The start screen: the paired wallets. */
    const val WALLETS = "wallets"
    const val SETTINGS = "settings"
    const val PAIR_SCAN = "pair-scan"
    const val PAIR_REVIEW = "pair-review"
    const val DATA_PROVIDERS = "settings/providers"
    const val LICENCES = "settings/licences"

    /** The pages of the settings list below the list: Display, Transactions, Networks, Wallets, the address books, Security, About and Developer. */
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_TRANSACTIONS = "settings/transactions"
    const val SETTINGS_NETWORKS = "settings/networks"
    const val SETTINGS_WALLETS = "settings/wallets"
    const val SETTINGS_ADDRESSES = "settings/addresses"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_ABOUT = "settings/about"

    /** The App Password change, opened from the Security page. */
    const val CHANGE_PASSWORD = "settings/security/password"

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

    /** The argument of the gesture change: the gesture's stored number. */
    const val GESTURE_ARG = "gesture"

    /** The Hidden Entry Gesture's choice, and the change to the gesture named by [GESTURE_ARG]. Both are shared with Lensly's screens. */
    const val ENTRY_GESTURE = "settings/gesture"
    const val ENTRY_GESTURE_CHANGE = "settings/gesture/change/{$GESTURE_ARG}"

    /** The route of the change to [gesture], which takes its stored number as [GESTURE_ARG]. */
    fun entryGestureChange(gesture: EntryGesture): String = "settings/gesture/change/${gesture.storedId}"

    /** The argument of a paired wallet's routes: the wallet's ID. */
    const val WALLET_ARG = "walletId"

    /** A paired wallet's accounts, with the wallet's ID as [WALLET_ARG]. */
    const val WALLET = "wallet/{$WALLET_ARG}"

    /** The route of the accounts of [walletId]. */
    fun wallet(walletId: String): String = "wallet/$walletId"

    /** The first sync of the wallet named by [WALLET_ARG], opened when its pairing is saved. */
    const val FIRST_SYNC = "first-sync/{$WALLET_ARG}"

    /** The route of the first sync of [walletId]. */
    fun firstSync(walletId: String): String = "first-sync/$walletId"

    /** The argument of an account's route: the name of its chain, as [AccountChain.route]. */
    const val ACCOUNT_CHAIN_ARG = "chain"

    /** The argument of an account's route: the account's place among the accounts of its chain in the wallet. */
    const val ACCOUNT_INDEX_ARG = "index"

    /** One account of a wallet, with the wallet's ID, the chain and the place as its arguments. */
    const val ACCOUNT = "account/{$WALLET_ARG}/{$ACCOUNT_CHAIN_ARG}/{$ACCOUNT_INDEX_ARG}"

    /** The route of account [index] of [chain] in [walletId]. */
    fun account(walletId: String, chain: AccountChain, index: Int): String = "account/$walletId/${chain.route}/$index"

    /** The argument of an asset's route: the asset's key among the account's assets, as [AssetRows] keys them. */
    const val ASSET_KEY_ARG = "assetKey"

    /** One asset of an account, with the account's wallet, chain and place, and the asset's key, as its arguments. */
    const val ASSET = "asset/{$WALLET_ARG}/{$ACCOUNT_CHAIN_ARG}/{$ACCOUNT_INDEX_ARG}/{$ASSET_KEY_ARG}"

    /** The route of the asset [assetKey] of account [index] of [chain] in [walletId]. */
    fun asset(walletId: String, chain: AccountChain, index: Int, assetKey: String): String = "asset/$walletId/${chain.route}/$index/$assetKey"

    /** The route of the read-only Address Book of [walletId], opened from the wallet's row in Settings. */
    const val ADDRESS_BOOK = "settings/address-book/{$WALLET_ARG}"

    fun addressBook(walletId: String): String = "settings/address-book/$walletId"

    /** The argument of the send's route: the index of the address that sends. */
    const val SEND_INDEX_ARG = "index"

    /** The argument of the send's route: the chain ID of the network the send is on. */
    const val SEND_CHAIN_ARG = "chain"

    /** The Ethereum send from one address of the wallet in [WALLET_ARG], on the network [SEND_CHAIN_ARG]. */
    const val SEND = "send/{$WALLET_ARG}/{$SEND_INDEX_ARG}?$SEND_CHAIN_ARG={$SEND_CHAIN_ARG}"

    /** The Solana send from the account at [SEND_INDEX_ARG] of the wallet in [WALLET_ARG]. Its side (testnet or mainnet) is the selected side. */
    const val SOLANA_SEND = "solana-send/{$WALLET_ARG}/{$SEND_INDEX_ARG}"

    /** The route of the Solana send from account [index] of [walletId]. */
    fun solanaSend(walletId: String, index: Int): String = "solana-send/$walletId/$index"

    /** The Algorand send from the account at [SEND_INDEX_ARG] of the wallet in [WALLET_ARG]. Its side (testnet or mainnet) is the selected side. */
    const val ALGORAND_SEND = "algorand-send/{$WALLET_ARG}/{$SEND_INDEX_ARG}"

    /** The route of the Algorand send from account [index] of [walletId]. */
    fun algorandSend(walletId: String, index: Int): String = "algorand-send/$walletId/$index"

    /** The Add asset screen of the Algorand account at [SEND_INDEX_ARG] of the wallet in [WALLET_ARG]. */
    const val ALGORAND_ADD_ASSET = "algorand-add-asset/{$WALLET_ARG}/{$SEND_INDEX_ARG}"

    /** The route of the Add asset screen of the Algorand account [index] of [walletId]. */
    fun algorandAddAsset(walletId: String, index: Int): String = "algorand-add-asset/$walletId/$index"

    /** The argument of an asset: the ID of the Algorand asset that an opt-in adds to the account. */
    const val ASSET_ARG = "asset"

    /** The opt-in of the Algorand asset in [ASSET_ARG] to the account at [SEND_INDEX_ARG] of the wallet in [WALLET_ARG]. */
    const val ALGORAND_OPT_IN = "algorand-opt-in/{$WALLET_ARG}/{$SEND_INDEX_ARG}/{$ASSET_ARG}"

    /** The route of the opt-in of asset [assetId] to the Algorand account [index] of [walletId]. */
    fun algorandOptIn(walletId: String, index: Int, assetId: ULong): String = "algorand-opt-in/$walletId/$index/$assetId"

    /** The Bitcoin send from the account at [SEND_INDEX_ARG] of the wallet in [WALLET_ARG]. Its side (testnet4 or mainnet) is the selected side. */
    const val BTC_SEND = "bitcoin-send/{$WALLET_ARG}/{$SEND_INDEX_ARG}"

    /** The route of the Bitcoin send from account [index] of [walletId]. */
    fun btcSend(walletId: String, index: Int): String = "bitcoin-send/$walletId/$index"

    /** The route of a send from address [index] of [walletId] on the network [chainId]. */
    fun send(walletId: String, index: Int, chainId: Long): String = "send/$walletId/$index?$SEND_CHAIN_ARG=$chainId"

    /** The receive page of one account, with the wallet's ID, the chain and the place as its arguments. */
    const val RECEIVE = "receive/{$WALLET_ARG}/{$ACCOUNT_CHAIN_ARG}/{$ACCOUNT_INDEX_ARG}"

    /** The route of the receive page of account [index] of [chain] in [walletId]. */
    fun receive(walletId: String, chain: AccountChain, index: Int): String = "receive/$walletId/${chain.route}/$index"

    /** The arguments of a transaction's page: the account that the row is for, and the transaction's id. */
    const val TX_ACCOUNT_ARG = "account"
    const val TX_ID_ARG = "txId"

    /** A transaction's page, for the row of [TX_ACCOUNT_ARG] and [TX_ID_ARG] in the wallet [WALLET_ARG], whose Address Book the addresses are named in. */
    const val TRANSACTION = "transaction/{$WALLET_ARG}/{$TX_ACCOUNT_ARG}/{$TX_ID_ARG}"

    /** The route of the page of transaction [txId] for [account], an account of the wallet [walletId]. */
    fun transaction(walletId: String, account: String, txId: String): String = "transaction/$walletId/$account/$txId"
}

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
 * The side of the networks the owner chose. The wallet screens read it, so the testnet strip shows on every Lumio screen while the side is testnet.
 * [choose] applies a side at once, and passes it to [store], which keeps it in the vault.
 */
internal class NetworkSelection(private val store: (NetworkMode) -> Unit = {}) {
    var mode by mutableStateOf(NetworkMode.MAINNET)

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
}

/**
 * A Lumio screen in the Hidden Area: the shared backdrop in Lumio's theme, with the testnet strip under the status bar while [selection] is testnet.
 * The gate screens are not Lumio's, so they keep the plain backdrop in Lensly's theme.
 */
@Composable
internal fun LumioBackdrop(selection: NetworkSelection, content: @Composable () -> Unit) {
    LumenTheme {
        MirrorNavMotion()
        HiddenBackdrop {
            var sideOpen by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxSize()) {
                // The band takes the status bar's height, and the strip sits under it. The screen below consumes the same inset, so its own
                // top bar meets the strip with no second gap.
                Box(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    if (selection.mode == NetworkMode.TESTNET) TestnetStrip(onOpen = { sideOpen = true })
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) { content() }
            }
            if (sideOpen) {
                NetworkSideSheet(
                    mode = selection.mode,
                    onSelect = { next ->
                        selection.choose(next)
                        sideOpen = false
                    },
                    onDismiss = { sideOpen = false },
                )
            }
        }
    }
}

/**
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the gate and
 * then shows the wallets. Settings opens from the gear on Wallets. A wallet opens its accounts, an account opens its Send and Receive, and the pairing
 * of a wallet from Lensly starts from the wallets. Settings is a list of pages, each its own screen: the network side, Display, Transactions, Networks
 * (the servers and the chains), Data providers, Address book (the wallets), Security, About and.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    // The shared-element scope lets a screen move one element to the next screen (section 3.3).
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
            LumioNavGraph(debugHiddenRequest = debugHiddenRequest, modifier = modifier)
        }
    }
}

@Composable
private fun LumioNavGraph(debugHiddenRequest: Int, modifier: Modifier) {
    val app = LocalContext.current.applicationContext as LumioApp
    val draft = remember { PairDraft() }
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
        hiddenArea(navController, holder, home = LumioRoute.WALLETS) {
            lumioComposable(LumioRoute.WALLETS) {
                // The wallets are the root screen and have no back arrow. System back locks the vault first, so leaving the app never
                // leaves the vault open, then closes the app as Android does at a root screen.
                val activity = LocalActivity.current
                BackHandler {
                    lock()
                    activity?.finish()
                }
                LumioBackdrop(selection) {
                    WalletsRoute(
                        navController = navController,
                        holder = holder,
                        selection = selection,
                        listVersion = listVersion,
                    )
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
                    TransactionDetailRoute(navController = navController, holder = holder, walletId = walletId, account = account, txId = txId)
                }
            }
            lumioComposable(LumioRoute.PAIR_SCAN) {
                LumioBackdrop(selection) { PairScanRoute(navController = navController, draft = draft) }
            }
            lumioComposable(
                route = LumioRoute.BTC_SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    BtcSendRoute(navController = navController, holder = holder, walletId = walletId, accountIndex = index, mode = selection.mode)
                }
            }
            lumioComposable(
                route = LumioRoute.SOLANA_SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    SolanaSendRoute(navController = navController, holder = holder, walletId = walletId, accountIndex = index, mode = selection.mode)
                }
            }
            lumioComposable(
                route = LumioRoute.ALGORAND_SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    AlgorandSendRoute(navController = navController, holder = holder, mode = selection.mode, walletId = walletId, accountIndex = index)
                }
            }
            lumioComposable(
                route = LumioRoute.ALGORAND_ADD_ASSET,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    AlgorandAddAssetRoute(navController = navController, holder = holder, mode = selection.mode, walletId = walletId, accountIndex = index)
                }
            }
            lumioComposable(
                route = LumioRoute.ALGORAND_OPT_IN,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                    navArgument(LumioRoute.ASSET_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                val assetId = entry.arguments?.getString(LumioRoute.ASSET_ARG)?.toULongOrNull()
                LumioBackdrop(selection) {
                    AlgorandSendRoute(
                        navController = navController,
                        holder = holder,
                        mode = selection.mode,
                        walletId = walletId,
                        accountIndex = index,
                        optInAssetId = assetId,
                    )
                }
            }
            settingsRoute(navController = navController, holder = holder, listVersion = listVersion, selection = selection)
            settingsPageRoutes(
                navController = navController,
                holder = holder,
                selection = selection,
                listVersion = listVersion,
                onChanged = { listVersion++ },
                lockChoices = lockChoices,
            )
            entryGestureRoutes(navController = navController, holder = holder, selection = selection)
            changePasswordRoutes(navController = navController, holder = holder, selection = selection)
            lumioComposable(LumioRoute.DATA_PROVIDERS) {
                LumioBackdrop(selection) { DataProvidersRoute(navController = navController, holder = holder) }
            }
            lumioComposable(LumioRoute.LICENCES) {
                LumioBackdrop(selection) { LicencesRoute(navController = navController) }
            }
            lumioComposable(
                route = LumioRoute.TOKENS,
                arguments = listOf(navArgument(LumioRoute.CHAIN_ARG) { type = NavType.LongType }),
            ) { entry ->
                val chainId = entry.arguments?.getLong(LumioRoute.CHAIN_ARG) ?: 0L
                LumioBackdrop(selection) { AddTokenRoute(navController = navController, holder = holder, chainId = chainId) }
            }
            lumioComposable(
                route = LumioRoute.ADDRESS_BOOK,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) { AddressBookRoute(navController = navController, holder = holder, walletId = walletId) }
            }
            lumioComposable(
                route = LumioRoute.WALLET,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    WalletRoute(navController = navController, holder = holder, walletId = walletId, mode = selection.mode, listVersion = listVersion)
                }
            }
            lumioComposable(
                route = LumioRoute.ACCOUNT,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_CHAIN_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val chain = AccountChain.ofRoute(entry.arguments?.getString(LumioRoute.ACCOUNT_CHAIN_ARG).orEmpty())
                val index = entry.arguments?.getInt(LumioRoute.ACCOUNT_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    if (chain != null) {
                        AccountRoute(
                            navController = navController,
                            holder = holder,
                            walletId = walletId,
                            chain = chain,
                            index = index,
                            mode = selection.mode,
                            listVersion = listVersion,
                        )
                    }
                }
            }
            lumioComposable(
                route = LumioRoute.ASSET,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_CHAIN_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
                    navArgument(LumioRoute.ASSET_KEY_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val chain = AccountChain.ofRoute(entry.arguments?.getString(LumioRoute.ACCOUNT_CHAIN_ARG).orEmpty())
                val index = entry.arguments?.getInt(LumioRoute.ACCOUNT_INDEX_ARG) ?: 0
                val assetKey = entry.arguments?.getString(LumioRoute.ASSET_KEY_ARG).orEmpty()
                LumioBackdrop(selection) {
                    if (chain != null) {
                        AssetRoute(
                            navController = navController,
                            holder = holder,
                            walletId = walletId,
                            chain = chain,
                            index = index,
                            assetKey = assetKey,
                            mode = selection.mode,
                            listVersion = listVersion,
                        )
                    }
                }
            }
            lumioComposable(
                route = LumioRoute.SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                    navArgument(LumioRoute.SEND_CHAIN_ARG) { type = NavType.LongType; defaultValue = NO_CHAIN },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                val chain = entry.arguments?.getLong(LumioRoute.SEND_CHAIN_ARG) ?: NO_CHAIN
                LumioBackdrop(selection) {
                    SendRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        index = index,
                        mode = selection.mode,
                        chainId = chain,
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.RECEIVE,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_CHAIN_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val chain = AccountChain.ofRoute(entry.arguments?.getString(LumioRoute.ACCOUNT_CHAIN_ARG).orEmpty())
                val index = entry.arguments?.getInt(LumioRoute.ACCOUNT_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    if (chain != null) {
                        ReceiveRoute(
                            navController = navController,
                            holder = holder,
                            walletId = walletId,
                            chain = chain,
                            index = index,
                            mode = selection.mode,
                        )
                    }
                }
            }
            lumioComposable(LumioRoute.PAIR_REVIEW) {
                BackHandler {
                    draft.clear()
                    navController.popBackStack(LumioRoute.WALLETS, inclusive = false)
                }
                LumioBackdrop(selection) {
                    PairReviewRoute(
                        navController = navController,
                        holder = holder,
                        draft = draft,
                        onChanged = { listVersion++ },
                    )
                }
            }
            lumioComposable(
                route = LumioRoute.FIRST_SYNC,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    FirstSyncRoute(navController = navController, holder = holder, walletId = walletId, mode = selection.mode)
                }
            }
        }
    }
}
