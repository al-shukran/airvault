package app.lumio

import androidx.activity.compose.BackHandler
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
import app.lumio.pair.Pairing

/** Lumio's own routes inside its Hidden Area. The gate's routes are shared, see `app.lensly.ui.navigation.GateRoutes`. */
internal object LumioRoute {
    const val HOME = "home"
    const val PAIR_SCAN = "pair-scan"
    const val PAIR_REVIEW = "pair-review"
    const val BITCOIN = "bitcoin"
    const val BITCOIN_ACCOUNT = "bitcoin-account"
    const val SETTINGS = "settings"
    const val EVM_SETTINGS = "evm-settings"

    /** The argument of the EVM portfolio's route: the paired wallet's ID. */
    const val WALLET_ARG = "walletId"

    /** The EVM portfolio's route, with the wallet's ID as [WALLET_ARG]. */
    const val WALLET = "wallet/{$WALLET_ARG}"

    /** The route of the EVM portfolio for [walletId]. */
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
 * Lumio's navigation: the camera cover and the gate that Lensly shares, around the companion's Hidden Area. The Hidden Area opens on the
 * gate and then shows Home. From Home the owner pairs a wallet from Lensly, through the scan and the review, opens a paired wallet's EVM
 * balances, changes the EVM settings, and deletes a paired wallet.
 *
 * [debugHiddenRequest] counts debug launches that asked for the hidden area. It is always zero in release builds.
 */
@Composable
fun LumioNavHost(debugHiddenRequest: Int = 0, modifier: Modifier = Modifier) {
    val draft = remember { PairDraft() }
    // Counts the saves and deletions, so Home reads the paired wallets again.
    var listVersion by remember { mutableIntStateOf(0) }
    CoverNavHost(debugHiddenRequest = debugHiddenRequest, modifier = modifier) { navController, holder ->
        fun lock() {
            draft.clear()
            leaveHidden(navController, holder)
        }
        hiddenArea(navController, holder, home = LumioRoute.HOME) {
            composable(LumioRoute.HOME) {
                BackHandler { lock() }
                HiddenBackdrop {
                    HomeRoute(
                        navController = navController,
                        holder = holder,
                        listVersion = listVersion,
                        onChanged = { listVersion++ },
                        onLock = ::lock,
                    )
                }
            }
            composable(LumioRoute.PAIR_SCAN) {
                PairScanRoute(navController = navController, draft = draft)
            }
            composable(LumioRoute.BITCOIN) {
                HiddenBackdrop { BitcoinRoute(navController = navController, holder = holder) }
            }
            composable(
                route = "${LumioRoute.BITCOIN_ACCOUNT}/{account}",
                arguments = listOf(navArgument("account") { type = NavType.StringType }),
            ) { entry ->
                val key = entry.arguments?.getString("account").orEmpty()
                HiddenBackdrop { BitcoinAccountRoute(navController = navController, holder = holder, accountKey = key) }
            }
            composable(LumioRoute.SETTINGS) {
                HiddenBackdrop { LumioSettingsRoute(navController = navController, holder = holder) }
            }
            composable(
                route = LumioRoute.WALLET,
                arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
            ) { entry ->
                val walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()
                HiddenBackdrop { WalletRoute(navController = navController, holder = holder, walletId = walletId) }
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
                HiddenBackdrop { SendRoute(navController = navController, holder = holder, walletId = walletId, index = index) }
            }
            composable(LumioRoute.EVM_SETTINGS) {
                HiddenBackdrop { EvmSettingsRoute(navController = navController, holder = holder) }
            }
            composable(LumioRoute.PAIR_REVIEW) {
                BackHandler {
                    draft.clear()
                    navController.popBackStack(LumioRoute.HOME, inclusive = false)
                }
                HiddenBackdrop {
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
