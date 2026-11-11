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
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.VaultHolder
import app.lensly.app.WalletMeta
import app.lensly.app.WalletStore
import app.lensly.app.defaultAccountLabel
import app.lensly.ui.wallet.WalletNameScreen
import app.lensly.ui.wallet.effectiveWalletName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rename routes of the hidden graph: a wallet's Display Name and one account's. Both use the shared name screen. Each
 * saves on DONE and returns to the wallet, whose detail reads the names again when it is shown.
 */
internal fun NavGraphBuilder.renameRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.RENAME_WALLET, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { RenameWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(
        HiddenRoute.RENAME_ACCOUNT,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
    ) { entry ->
        HiddenBackdrop { RenameAccountRoute(navController, holder, entry.walletId(), entry.accountIndex()) }
    }
}

/**
 * Renames wallet [walletId]. Its current name is the placeholder, and DONE with nothing typed keeps that name. Only the
 * wallet's name changes, and its key, its accounts and their names stay as they are.
 */
@Composable
private fun RenameWalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            val found = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
            if (found == null) navController.popBackStack() else wallet = found
        }
    }
    val current = wallet ?: return
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    WalletNameScreen(
        name = name,
        placeholder = current.name,
        onKey = { c -> if (!saving && name.length < DISPLAY_NAME_MAX_LENGTH) name += c },
        onBackspace = { if (!saving && name.isNotEmpty()) name = name.dropLast(1) },
        onDone = {
            if (!saving) {
                saving = true
                val finalName = effectiveWalletName(name, current.name)
                scope.launch {
                    whileUnlocked(navController, holder) {
                        withContext(Dispatchers.IO) { WalletStore(session).renameWallet(walletId, finalName) }
                        navController.popBackStack()
                    }
                }
            }
        },
    )
}

/**
 * Renames account [index] of wallet [walletId]. The placeholder is the account's current label. DONE with nothing typed
 * keeps the label it has. RESET shows only for a renamed account, and gives the account its default label back.
 */
@Composable
private fun RenameAccountRoute(navController: NavHostController, holder: VaultHolder, walletId: String, index: Int) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var loaded by remember(walletId, index) { mutableStateOf(false) }
    var customName by remember(walletId, index) { mutableStateOf<String?>(null) }
    LaunchedEffect(walletId, index) {
        whileUnlocked(navController, holder) {
            val store = WalletStore(session)
            val meta = withContext(Dispatchers.IO) { store.meta(walletId) }
            if (meta == null || index !in 0 until meta.accountCount) {
                navController.popBackStack()
                return@whileUnlocked
            }
            customName = withContext(Dispatchers.IO) { store.accountName(walletId, index) }
            loaded = true
        }
    }
    if (!loaded) return

    val defaultLabel = defaultAccountLabel(index)
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val reset: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                whileUnlocked(navController, holder) {
                    withContext(Dispatchers.IO) { WalletStore(session).resetAccountName(walletId, index) }
                    navController.popBackStack()
                }
            }
        }
    }

    WalletNameScreen(
        name = name,
        placeholder = customName ?: defaultLabel,
        onKey = { c -> if (!saving && name.length < DISPLAY_NAME_MAX_LENGTH) name += c },
        onBackspace = { if (!saving && name.isNotEmpty()) name = name.dropLast(1) },
        onDone = {
            if (!saving) {
                val typed = name.trim()
                if (typed.isEmpty()) {
                    // Nothing typed, so the account keeps the label it has. No record is written for it.
                    navController.popBackStack()
                } else {
                    saving = true
                    scope.launch {
                        whileUnlocked(navController, holder) {
                            withContext(Dispatchers.IO) { WalletStore(session).renameAccount(walletId, index, typed) }
                            navController.popBackStack()
                        }
                    }
                }
            }
        },
        onReset = if (customName != null) reset else null,
        resetLabel = "Reset to $defaultLabel",
    )
}

private fun NavBackStackEntry.accountIndex(): Int = requireNotNull(arguments).getInt(HiddenRoute.ACCOUNT_INDEX_ARG)
