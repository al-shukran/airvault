package app.lumio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.lensly.app.export.PairingPayload
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.navigation.CoverNavHost
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.hiddenArea
import app.lensly.ui.navigation.leaveHidden
import app.lumio.data.config.LockSettings
import app.lumio.data.config.LockSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.ui.network.TestnetBanner
import app.lumio.ui.settings.SettingsPage
import app.lumio.ui.shell.LumioTab
import app.lumio.ui.shell.LumioTabFrame

/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val PAIR_SCAN = "pair-scan"
    const val PAIR_REVIEW = "pair-review"
    const val BITCOIN_ACCOUNT = "bitcoin-account"
    const val SETTINGS = "settings"
    const val BTC_SEND = "bitcoin-send"
    const val DATA_PROVIDERS = "settings/providers"
    const val LICENCES = "settings/licences"

    /** The pages of the settings list below the list: Display, Transactions, Networks, the address books, Security, About and Developer. */
    const val SETTINGS_DISPLAY = "settings/display"
    const val SETTINGS_TRANSACTIONS = "settings/transactions"
    const val SETTINGS_NETWORKS = "settings/networks"
    const val SETTINGS_ADDRESSES = "settings/addresses"
    const val SETTINGS_SECURITY = "settings/security"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_DEVELOPER = "settings/developer"

    /** The route of the settings page [page]. Data providers keeps its own route, as its screen does. */
    fun settingsPage(page: SettingsPage): String = when (page) {
        SettingsPage.DISPLAY -> SETTINGS_DISPLAY
        SettingsPage.TRANSACTIONS -> SETTINGS_TRANSACTIONS
        SettingsPage.NETWORKS -> SETTINGS_NETWORKS
        SettingsPage.PROVIDERS -> DATA_PROVIDERS
        SettingsPage.ADDRESS_BOOK -> SETTINGS_ADDRESSES
        SettingsPage.SECURITY -> SETTINGS_SECURITY
        SettingsPage.ABOUT -> SETTINGS_ABOUT
        SettingsPage.DEVELOPER -> SETTINGS_DEVELOPER
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

    /** A paired wallet's Bitcoin portfolio, with the wallet's ID as [WALLET_ARG]. */
    const val BITCOIN_WALLET = "bitcoin/{$WALLET_ARG}"

    /** A paired wallet's EVM portfolio, with the wallet's ID as [WALLET_ARG]. */
    const val WALLET = "wallet/{$WALLET_ARG}"

    /** The route of the Bitcoin portfolio of [walletId]. */
    fun bitcoin(walletId: String): String = "bitcoin/$walletId"

    /** The route of the EVM portfolio of [walletId]. */
    fun wallet(walletId: String): String = "wallet/$walletId"

    /** The route of the read-only Address Book of [walletId], opened from the wallet's row in Settings. */
    const val ADDRESS_BOOK = "settings/address-book/{$WALLET_ARG}"

    fun addressBook(walletId: String): String = "settings/address-book/$walletId"

    /** The argument of the send's route: the index of the address that sends. */
    const val SEND_INDEX_ARG = "index"

    /** The optional argument of the send's route: the chain ID the form starts on. Absent, the form starts on its first chain. */
    const val SEND_CHAIN_ARG = "chain"

    /** The optional argument of the send's route: the contract of the ERC-20 token the form starts on. Absent, it starts on the native coin. */
    const val SEND_TOKEN_ARG = "token"

    /** The send from one address of the wallet in [WALLET_ARG], starting on the chain and the token in the optional arguments when they are given. */
    const val SEND = "send/{$WALLET_ARG}/{$SEND_INDEX_ARG}?$SEND_CHAIN_ARG={$SEND_CHAIN_ARG}&$SEND_TOKEN_ARG={$SEND_TOKEN_ARG}"

    /** The route of a send from address [index] of [walletId], starting on [chainId] and on the token [token] when they are given. */
    fun send(walletId: String, index: Int, chainId: Long? = null, token: String? = null): String {
        val query = listOfNotNull(chainId?.let { "$SEND_CHAIN_ARG=$it" }, token?.let { "$SEND_TOKEN_ARG=$it" })
        return "send/$walletId/$index" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

    /** The argument of a coin's page: the coin's ID, such as "EVM-1". */
    const val ASSET_ARG = "assetId"

    /** A coin's page, with its ID as [ASSET_ARG]. */
    const val ASSET = "asset/{$ASSET_ARG}"

    /** The route of the page of the coin [assetId]. */
    fun asset(assetId: String): String = "asset/$assetId"

    /** The kind of a receive to an Ethereum account: its key is the wallet's ID. */
    const val KIND_EVM = "evm"

    /** The kind of a receive to a Bitcoin account: its key is the account's key. */
    const val KIND_BTC = "btc"

    /** The arguments of a receive's route: its [RECEIVE_KIND_ARG] and its [RECEIVE_KEY_ARG]. */
    const val RECEIVE_KIND_ARG = "kind"
    const val RECEIVE_KEY_ARG = "key"

    /** The receive page of one account. */
    const val RECEIVE = "receive/{$RECEIVE_KIND_ARG}/{$RECEIVE_KEY_ARG}"

    /** The route of the receive page of the account [key] of [kind], which is [KIND_EVM] or [KIND_BTC]. */
    fun receive(kind: String, key: String): String = "receive/$kind/$key"

    /** The arguments of a transaction's page: the account that the row is for, and the transaction's id. */
    const val TX_ACCOUNT_ARG = "account"
    const val TX_ID_ARG = "txId"

    /** A transaction's page, for the row of [TX_ACCOUNT_ARG] and [TX_ID_ARG]. */
    const val TRANSACTION = "transaction/{$TX_ACCOUNT_ARG}/{$TX_ID_ARG}"

    /** The route of the page of transaction [txId] for [account]. */
    fun transaction(account: String, txId: String): String = "transaction/$account/$txId"
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
 * The side of the networks the owner chose on Home. Home reads it from the vault when it opens and writes it when the owner switches. Every
 * Lumio screen reads it, so the testnet banner shows on all of them while the side is testnet.
 */
internal class NetworkSelection {
    var mode by mutableStateOf(NetworkMode.MAINNET)
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
 * A Lumio screen in the Hidden Area: the shared backdrop, with the testnet banner above the screen while [selection] is testnet. The gate
 * screens are not Lumio's, so they keep the plain backdrop.
 */
@Composable
internal fun LumioBackdrop(selection: NetworkSelection, content: @Composable () -> Unit) {
    HiddenBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selection.mode == NetworkMode.TESTNET) TestnetBanner()
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

/**
 * Opens [tab]. The tabs are root screens: a tab opens over Home, and Home is never popped by a tab, so the stack holds Home and at most one tab
 * above it.
 */
internal fun NavHostController.openTab(tab: LumioTab) {
    val route = when (tab) {
        LumioTab.HOME -> LumioRoute.HOME
        LumioTab.TRANSACTIONS -> LumioRoute.TRANSACTIONS
        LumioTab.SETTINGS -> LumioRoute.SETTINGS
    }
    navigate(route) {
        popUpTo(LumioRoute.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the gate and
 * then shows Home. Home, Transactions and Settings are the tabs. From Home the owner sends or receives, opens an asset, and pairs a wallet from Lensly
 * through the scan and the review. Settings is a list of pages, each its own screen: Display, Transactions, Networks (the servers and the chains), Data providers, Address book (the wallets), Security, About and, in a debug build, Developer.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as LumioApp
    val draft = remember { PairDraft() }
    val selection = remember { NetworkSelection() }
    val lockChoices = remember { LockSettingsState(app.lockSettings) }
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
        hiddenArea(navController, holder, home = LumioRoute.HOME) {
            composable(LumioRoute.HOME) {
                // Home is the root screen and has no back arrow. System back locks the vault first, so leaving the app never
                // leaves the vault open, then closes the app as Android does at a root screen.
                val activity = LocalActivity.current
                BackHandler {
                    lock()
                    activity?.finish()
                }
                LumioBackdrop(selection) {
                    LumioTabFrame(selected = LumioTab.HOME, onSelect = { navController.openTab(it) }) {
                        HomeRoute(
                            navController = navController,
                            holder = holder,
                            selection = selection,
                            listVersion = listVersion,
                            onLock = ::lock,
                        )
                    }
                }
            }
            composable(LumioRoute.TRANSACTIONS) {
                LumioBackdrop(selection) {
                    LumioTabFrame(selected = LumioTab.TRANSACTIONS, onSelect = { navController.openTab(it) }) {
                        TransactionsRoute(navController = navController, holder = holder, mode = selection.mode)
                    }
                }
            }
            composable(
                route = LumioRoute.TRANSACTION,
                arguments = listOf(
                    navArgument(LumioRoute.TX_ACCOUNT_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.TX_ID_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val account = entry.arguments?.getString(LumioRoute.TX_ACCOUNT_ARG).orEmpty()
                val txId = entry.arguments?.getString(LumioRoute.TX_ID_ARG).orEmpty()
                LumioBackdrop(selection) {
                    TransactionDetailRoute(navController = navController, holder = holder, account = account, txId = txId)
                }
            }
            composable(LumioRoute.PAIR_SCAN) {
                LumioBackdrop(selection) { PairScanRoute(navController = navController, draft = draft) }
            }
            composable(
                route = LumioRoute.BITCOIN_WALLET,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    BitcoinRoute(navController = navController, holder = holder, walletId = walletId)
                }
            }
            composable(
                route = "${LumioRoute.BITCOIN_ACCOUNT}/{account}",
                arguments = listOf(navArgument("account") { type = NavType.StringType }),
            ) { entry ->
                val key = entry.arguments?.getString("account").orEmpty()
                LumioBackdrop(selection) {
                    BitcoinAccountRoute(navController = navController, holder = holder, accountKey = key)
                }
            }
            composable(LumioRoute.BTC_SEND) {
                LumioBackdrop(selection) { BtcSendRoute(navController = navController, holder = holder, mode = selection.mode) }
            }
            composable(LumioRoute.SETTINGS) {
                LumioBackdrop(selection) {
                    LumioTabFrame(selected = LumioTab.SETTINGS, onSelect = { navController.openTab(it) }) {
                        SettingsRoute(navController = navController, holder = holder, listVersion = listVersion)
                    }
                }
            }
            settingsPageRoutes(
                navController = navController,
                holder = holder,
                selection = selection,
                listVersion = listVersion,
                onChanged = { listVersion++ },
                lockChoices = lockChoices,
            )
            entryGestureRoutes(navController = navController, holder = holder, selection = selection)
            composable(LumioRoute.DATA_PROVIDERS) {
                LumioBackdrop(selection) { DataProvidersRoute(navController = navController, holder = holder) }
            }
            composable(LumioRoute.LICENCES) {
                LumioBackdrop(selection) { LicencesRoute(navController = navController) }
            }
            composable(
                route = LumioRoute.TOKENS,
                arguments = listOf(navArgument(LumioRoute.CHAIN_ARG) { type = NavType.LongType }),
            ) { entry ->
                val chainId = entry.arguments?.getLong(LumioRoute.CHAIN_ARG) ?: 0L
                LumioBackdrop(selection) { AddTokenRoute(navController = navController, holder = holder, chainId = chainId) }
            }
            composable(
                route = LumioRoute.ADDRESS_BOOK,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) { AddressBookRoute(navController = navController, holder = holder, walletId = walletId) }
            }
            composable(
                route = LumioRoute.WALLET,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    WalletRoute(navController = navController, holder = holder, walletId = walletId, mode = selection.mode)
                }
            }
            composable(
                route = LumioRoute.SEND,
                arguments = listOf(
                    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.SEND_INDEX_ARG) { type = NavType.IntType },
                    navArgument(LumioRoute.SEND_CHAIN_ARG) { type = NavType.LongType; defaultValue = NO_CHAIN },
                    navArgument(LumioRoute.SEND_TOKEN_ARG) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                val chain = entry.arguments?.getLong(LumioRoute.SEND_CHAIN_ARG) ?: NO_CHAIN
                val token = entry.arguments?.getString(LumioRoute.SEND_TOKEN_ARG).orEmpty()
                LumioBackdrop(selection) {
                    SendRoute(
                        navController = navController,
                        holder = holder,
                        walletId = walletId,
                        index = index,
                        mode = selection.mode,
                        preferredChainId = chain.takeIf { it != NO_CHAIN },
                        preferredToken = token.takeIf { it.isNotEmpty() },
                    )
                }
            }
            composable(
                route = LumioRoute.ASSET,
                arguments = listOf(navArgument(LumioRoute.ASSET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val assetId = entry.arguments?.getString(LumioRoute.ASSET_ARG).orEmpty()
                LumioBackdrop(selection) {
                    AssetRoute(navController = navController, holder = holder, assetId = assetId, selection = selection)
                }
            }
            composable(
                route = LumioRoute.RECEIVE,
                arguments = listOf(
                    navArgument(LumioRoute.RECEIVE_KIND_ARG) { type = NavType.StringType },
                    navArgument(LumioRoute.RECEIVE_KEY_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val kind = entry.arguments?.getString(LumioRoute.RECEIVE_KIND_ARG).orEmpty()
                val key = entry.arguments?.getString(LumioRoute.RECEIVE_KEY_ARG).orEmpty()
                LumioBackdrop(selection) {
                    ReceiveRoute(navController = navController, holder = holder, kind = kind, key = key)
                }
            }
            composable(LumioRoute.PAIR_REVIEW) {
                BackHandler {
                    draft.clear()
                    navController.popBackStack(LumioRoute.HOME, inclusive = false)
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
        }
    }
}
