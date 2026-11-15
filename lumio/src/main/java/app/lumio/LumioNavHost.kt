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
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.lensly.ui.navigation.CoverNavHost
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.hiddenArea
import app.lensly.ui.navigation.leaveHidden
import app.lumio.network.NetworkMode
import app.lumio.pair.Pairing
import app.lumio.ui.network.TestnetBanner
import app.lumio.ui.settings.LumioSettingsScreen

/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    const val HOME = "home"
    const val PAIR_SCAN = "pair-scan"
    const val PAIR_REVIEW = "pair-review"
    const val BITCOIN_ACCOUNT = "bitcoin-account"
    const val SETTINGS = "settings"
    const val BTC_SEND = "bitcoin-send"
    const val DATA_PROVIDERS = "settings/providers"

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

    /** The argument of the send's route: the index of the address that sends. */
    const val SEND_INDEX_ARG = "index"

    /** The send from one address of the wallet in [WALLET_ARG]. */
    const val SEND = "send/{$WALLET_ARG}/{$SEND_INDEX_ARG}"

    /** The route of a send from address [index] of [walletId]. */
    fun send(walletId: String, index: Int): String = "send/$walletId/$index"
}

/**
 * The pairing that a scan has read and the owner has not saved yet. It holds public data only, and it is dropped on save, on cancel and on
 * lock.
 */
internal class PairDraft {
    var pairing by mutableStateOf<Pairing?>(null)

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
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the
 * gate and then shows Home. From Home the owner pairs a wallet from Lensly, through the scan and the review, opens a paired wallet's Apps
 * (Bitcoin and Ethereum), changes the settings, and deletes a paired wallet.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    val draft = remember { PairDraft() }
    val selection = remember { NetworkSelection() }
    // Counts the saves and deletions, so Home reads the paired wallets again.
    var listVersion by remember { mutableIntStateOf(0) }
    CoverNavHost(debugHiddenRequest = debugHiddenRequest, modifier = modifier) { navController, holder ->
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
                    HomeRoute(
                        navController = navController,
                        holder = holder,
                        selection = selection,
                        listVersion = listVersion,
                        onChanged = { listVersion++ },
                    )
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
                    LumioSettingsScreen(
                        onBack = { navController.popBackStack() },
                        bitcoin = { BitcoinSettingsRoute(navController = navController, holder = holder) },
                        ethereum = { EvmSettingsRoute(navController = navController, holder = holder) },
                        onDataProviders = { navController.navigate(LumioRoute.DATA_PROVIDERS) },
                    )
                }
            }
            composable(LumioRoute.DATA_PROVIDERS) {
                LumioBackdrop(selection) { DataProvidersRoute(navController = navController, holder = holder) }
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
                ),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                val index = entry.arguments?.getInt(LumioRoute.SEND_INDEX_ARG) ?: 0
                LumioBackdrop(selection) {
                    SendRoute(navController = navController, holder = holder, walletId = walletId, index = index, mode = selection.mode)
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
