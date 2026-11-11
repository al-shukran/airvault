package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.batchBaseMaxLength
import app.lensly.ui.wallet.WalletNameScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rename route of the hidden graph: a batch of accounts' Display Names, on the shared name screen. It saves on DONE and returns
 * to the wallet, whose detail reads the names again when it is shown. A single account's name, its colour, icon, label and group,
 * and a wallet's profile are edited on their profile screens.
 */
internal fun NavGraphBuilder.renameRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(
        HiddenRoute.RENAME_ACCOUNTS,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDICES_ARG) { type = NavType.StringType },
    ) { entry ->
        HiddenBackdrop {
            RenameAccountsRoute(navController, holder, entry.walletId(), entry.accountIndices())
        }
    }
}

/**
 * Batch Rename: gives each selected account of wallet [walletId] the name "<base> <index>", built from the base typed here, in
 * one write. The base is limited so that the longest generated name still fits. DONE with nothing typed keeps every account
 * as it is. A route whose accounts do not all exist goes back without saving.
 */
@Composable
private fun RenameAccountsRoute(navController: NavHostController, holder: VaultHolder, walletId: String, indices: List<Int>) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var loaded by remember(walletId, indices) { mutableStateOf(false) }
    LaunchedEffect(walletId, indices) {
        whileUnlocked(navController, holder) {
            val meta = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
            if (meta == null || indices.isEmpty() || indices.any { it !in 0 until meta.accountCount }) {
                navController.popBackStack()
            } else {
                loaded = true
            }
        }
    }
    if (!loaded) return

    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val limit = batchBaseMaxLength(indices)

    WalletNameScreen(
        name = name,
        title = "Account names",
        placeholder = "Base name",
        emptyHelp = "Leave empty to keep their names",
        maxLength = limit,
        onKey = { c -> if (!saving && name.length < limit) name += c },
        onBackspace = { if (!saving && name.isNotEmpty()) name = name.dropLast(1) },
        onDone = {
            if (!saving) {
                val typed = name.trim()
                if (typed.isEmpty()) {
                    // Nothing typed, so every account keeps its name. No record is written.
                    navController.popBackStack()
                } else {
                    saving = true
                    scope.launch {
                        whileUnlocked(navController, holder) {
                            withContext(Dispatchers.IO) { WalletStore(session).renameAccounts(walletId, indices, typed) }
                            navController.popBackStack()
                        }
                    }
                }
            }
        },
    )
}

internal fun NavBackStackEntry.accountIndex(): Int = requireNotNull(arguments).getInt(HiddenRoute.ACCOUNT_INDEX_ARG)

/**
 * The accounts of a batch route, from its comma-separated list. A malformed list is empty, so the route goes back. Shared by the
 * batch rename and the batch style routes.
 */
internal fun NavBackStackEntry.accountIndices(): List<Int> {
    val parts = arguments?.getString(HiddenRoute.ACCOUNT_INDICES_ARG).orEmpty().split(",")
    val indices = parts.mapNotNull { it.toIntOrNull() }
    return if (indices.size == parts.size) indices else emptyList()
}
