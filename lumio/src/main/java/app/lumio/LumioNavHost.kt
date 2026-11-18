package app.lumio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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
import app.lumio.send.algorand.AlgorandSendRoute
import app.lumio.send.solana.SolanaSendRoute
import app.lumio.ui.network.TestnetBanner
import app.lumio.ui.settings.SettingsPage
import app.lumio.ui.shell.LumioTab
import app.lumio.ui.shell.LumioTabFrame
import app.lumio.ui.theme.LumioTheme
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

    /** The argument of an account's route: the name of its chain, as [AccountChain.route]. */
    const val ACCOUNT_CHAIN_ARG = "chain"

    /** The argument of an account's route: the account's place among the accounts of its chain in the wallet. */
    const val ACCOUNT_INDEX_ARG = "index"

    /** One account of a wallet, with the wallet's ID, the chain and the place as its arguments. */
    const val ACCOUNT = "account/{$WALLET_ARG}/{$ACCOUNT_CHAIN_ARG}/{$ACCOUNT_INDEX_ARG}"

    /** The route of account [index] of [chain] in [walletId]. */
    fun account(walletId: String, chain: AccountChain, index: Int): String = "account/$walletId/${chain.route}/$index"

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
 * The side of the networks the owner chose in Settings. Settings writes it to the vault when the owner switches, and the wallet screens read it, so the
 * testnet banner shows on every Lumio screen while the side is testnet.
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
 * A Lumio screen in the Hidden Area: the shared backdrop in Lumio's theme, with the testnet banner above the screen while [selection] is testnet.
 * The gate screens are not Lumio's, so they keep the plain backdrop in Lensly's theme.
 */
@Composable
internal fun LumioBackdrop(selection: NetworkSelection, content: @Composable () -> Unit) {
    LumioTheme {
        HiddenBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selection.mode == NetworkMode.TESTNET) TestnetBanner()
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }
    }
}

/**
 * Opens [tab]. The tabs are root screens: a tab opens over Home, and Home is never popped by a tab, so the stack holds Home and at most one tab
 * above it.
 */
internal fun NavHostController.openTab(tab: LumioTab) {
    val route = when (tab) {
        LumioTab.WALLETS -> LumioRoute.WALLETS
        LumioTab.SETTINGS -> LumioRoute.SETTINGS
    }
    navigate(route) {
        popUpTo(LumioRoute.WALLETS) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the gate and
 * then shows the wallets. Wallets and Settings are the two tabs. A wallet opens its accounts, an account opens its Send and Receive, and the pairing
 * of a wallet from Lensly starts from the wallets. Settings is a list of pages, each its own screen: the network side, Display, Transactions, Networks
 * (the servers and the chains), Data providers, Address book (the wallets), Security, About and.
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
        hiddenArea(navController, holder, home = LumioRoute.WALLETS) {
            composable(LumioRoute.WALLETS) {
                // The wallets are the root screen and have no back arrow. System back locks the vault first, so leaving the app never
                // leaves the vault open, then closes the app as Android does at a root screen.
                val activity = LocalActivity.current
                BackHandler {
                    lock()
                    activity?.finish()
                }
                LumioBackdrop(selection) {
                    LumioTabFrame(selected = LumioTab.WALLETS, onSelect = { navController.openTab(it) }) {
                        WalletsRoute(
                            navController = navController,
                            holder = holder,
                            selection = selection,
                            listVersion = listVersion,
                            onLock = ::lock,
                        )
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
            composable(
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
            composable(
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
            composable(LumioRoute.SETTINGS) {
                LumioBackdrop(selection) {
                    LumioTabFrame(selected = LumioTab.SETTINGS, onSelect = { navController.openTab(it) }) {
                        SettingsRoute(navController = navController, holder = holder, listVersion = listVersion, selection = selection)
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
            changePasswordRoutes(navController = navController, holder = holder, selection = selection)
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
                    WalletRoute(navController = navController, holder = holder, walletId = walletId, mode = selection.mode, listVersion = listVersion)
                }
            }
            composable(
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
            composable(
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
            composable(
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
            composable(LumioRoute.PAIR_REVIEW) {
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
        }
    }
}
