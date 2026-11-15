package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.lensly.app.AccountDraft
import app.lensly.app.AccountProfile
import app.lensly.app.StyleChange
import app.lensly.app.StyleEdits
import app.lensly.app.VaultHolder
import app.lensly.app.VisualIdentity
import app.lensly.app.WalletProfileDraft
import app.lensly.app.WalletStore
import app.lensly.app.autoAccountName
import app.lensly.app.generatedIdentity
import app.lensly.ui.profile.AccountProfileScreen
import app.lensly.ui.profile.StyleAccountsScreen
import app.lensly.ui.profile.WalletProfileScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The profile routes of the hidden graph: a wallet's profile, one account's profile, and the style of a batch of accounts. Each
 * saves on DONE and returns to the wallet, whose detail reads the profiles again when it is shown.
 */
internal fun NavGraphBuilder.profileRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.EDIT_WALLET, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { EditWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(
        HiddenRoute.EDIT_ACCOUNT,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
    ) { entry ->
        HiddenBackdrop { EditAccountRoute(navController, holder, entry.walletId(), entry.accountIndex()) }
    }
    composable(
        HiddenRoute.EDIT_BITCOIN_ACCOUNT,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
    ) { entry ->
        HiddenBackdrop { EditBitcoinAccountRoute(navController, holder, entry.walletId(), entry.accountIndex()) }
    }
    composable(
        HiddenRoute.STYLE_ACCOUNTS,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDICES_ARG) { type = NavType.StringType },
    ) { entry ->
        HiddenBackdrop { StyleAccountsRoute(navController, holder, entry.walletId(), entry.accountIndices()) }
    }
}

/**
 * An account being edited: its draft, its Auto-Name, the groups the accounts of its App already use, and the look it gets when it has
 * none. An Ethereum account and a Bitcoin account are edited the same way, so both share [AccountProfileRoute].
 */
private class AccountEditor(
    val draft: AccountDraft,
    val autoName: String,
    val groups: List<String>,
    val generated: VisualIdentity,
)

/** A wallet being edited: its draft, its current name, and its place in the wallet list, which sets its generated look. */
private class WalletEditor(val draft: WalletProfileDraft, val currentName: String, val position: Int)

/**
 * The profile of Ethereum account [index] of wallet [walletId]. The draft starts from what the account has, and the colour and icon it
 * does not have are the generated ones. An index the wallet does not have goes back without a screen.
 */
@Composable
private fun EditAccountRoute(navController: NavHostController, holder: VaultHolder, walletId: String, index: Int) {
    AccountProfileRoute(
        navController = navController,
        holder = holder,
        walletId = walletId,
        index = index,
        load = { store ->
            val meta = store.meta(walletId)
            if (meta == null || index !in 0 until meta.accountCount) {
                null
            } else {
                AccountEditor(
                    draft = AccountDraft.of(store.accountProfile(walletId, index), generatedIdentity(index)),
                    autoName = autoAccountName(meta.autoNameBase, index),
                    groups = store.accountProfiles(walletId, meta.accountCount).values.mapNotNull { it.group }.distinct().sorted(),
                    generated = generatedIdentity(index),
                )
            }
        },
        save = { store, profile -> store.saveAccountProfile(walletId, index, profile) },
    )
}

/**
 * The profile of Bitcoin account [position] of wallet [walletId], where the position is the account's place in the wallet's Bitcoin
 * accounts. It is edited as an Ethereum account is, and its look is generated from its position, as the Ethereum one is from its index.
 */
@Composable
private fun EditBitcoinAccountRoute(navController: NavHostController, holder: VaultHolder, walletId: String, position: Int) {
    AccountProfileRoute(
        navController = navController,
        holder = holder,
        walletId = walletId,
        index = position,
        load = { store ->
            val meta = store.meta(walletId)
            val accounts = store.bitcoinAccounts(walletId)
            val account = accounts.getOrNull(position)
            if (meta == null || account == null) {
                null
            } else {
                AccountEditor(
                    draft = AccountDraft.of(account.profile, generatedIdentity(position)),
                    autoName = autoAccountName(meta.autoNameBase, account.index),
                    groups = accounts.mapNotNull { it.profile.group }.distinct().sorted(),
                    generated = generatedIdentity(position),
                )
            }
        },
        save = { store, profile ->
            val account = requireNotNull(store.bitcoinAccounts(walletId).getOrNull(position)) { "No Bitcoin account $position." }
            store.saveBitcoinProfile(walletId, account, profile)
        },
    )
}

/**
 * The profile editor of one account, shared by Ethereum and Bitcoin accounts. [load] reads the editor's state, or null when the account
 * is gone, and [save] writes the profile on DONE. A save that the store refuses, because the profile does not fit, stays on the editor
 * with the reason, so the owner can shorten it.
 */
@Composable
private fun AccountProfileRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    index: Int,
    load: (WalletStore) -> AccountEditor?,
    save: (WalletStore, AccountProfile) -> Unit,
) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var editor by remember(walletId, index) { mutableStateOf<AccountEditor?>(null) }
    LaunchedEffect(walletId, index) {
        whileUnlocked(navController, holder) {
            val loaded = withContext(Dispatchers.IO) { load(WalletStore(session)) }
            if (loaded == null) {
                navController.popBackStack()
            } else {
                editor = loaded
            }
        }
    }
    val current = editor ?: return
    var draft by remember(walletId, index) { mutableStateOf(current.draft) }
    var saving by remember(walletId, index) { mutableStateOf(false) }
    var refusal by remember(walletId, index) { mutableStateOf<String?>(null) }

    AccountProfileScreen(
        draft = draft,
        autoName = current.autoName,
        groups = current.groups,
        error = refusal,
        onFocus = { field -> draft = draft.focusOn(field) },
        onKey = { c -> if (!saving) draft = draft.typing(c) },
        onBackspace = { if (!saving) draft = draft.backspace() },
        onPickColour = { colour -> draft = draft.withColour(colour) },
        onPickIcon = { icon -> draft = draft.withIcon(icon) },
        onPickGroup = { group -> draft = draft.withGroup(group) },
        onResetName = { draft = draft.resetName() },
        onResetStyle = { draft = draft.resetStyle(current.generated) },
        onSave = {
            if (!saving) {
                saving = true
                refusal = null
                val profile = draft.toProfile(current.generated)
                scope.launch {
                    whileUnlocked(navController, holder) {
                        try {
                            withContext(Dispatchers.IO) { save(WalletStore(session), profile) }
                            navController.popBackStack()
                        } catch (e: IllegalArgumentException) {
                            refusal = e.message
                            saving = false
                        }
                    }
                }
            }
        },
    )
}

/**
 * The profile of wallet [walletId]: its name, label and groups, and its colour and icon. A blank name keeps the current name. The
 * name and the profile are saved together on DONE, and the accounts keep theirs.
 */
@Composable
private fun EditWalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var editor by remember(walletId) { mutableStateOf<WalletEditor?>(null) }
    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            val store = WalletStore(session)
            val loaded = withContext(Dispatchers.IO) {
                val meta = store.meta(walletId)
                if (meta == null) {
                    null
                } else {
                    val position = store.list().indexOfFirst { it.id == walletId }.coerceAtLeast(0)
                    WalletEditor(
                        draft = WalletProfileDraft.of(meta.name, store.walletProfile(walletId), generatedIdentity(position)),
                        currentName = meta.name,
                        position = position,
                    )
                }
            }
            if (loaded == null) {
                navController.popBackStack()
            } else {
                editor = loaded
            }
        }
    }
    val current = editor ?: return
    var draft by remember(walletId) { mutableStateOf(current.draft) }
    var saving by remember(walletId) { mutableStateOf(false) }

    WalletProfileScreen(
        draft = draft,
        onFocus = { field -> draft = draft.focusOn(field) },
        onKey = { c -> if (!saving) draft = draft.typing(c) },
        onBackspace = { if (!saving) draft = draft.backspace() },
        onPickColour = { colour -> draft = draft.withColour(colour) },
        onPickIcon = { icon -> draft = draft.withIcon(icon) },
        onRemoveGroup = { group -> draft = draft.removeGroup(group) },
        onResetStyle = { draft = draft.resetStyle(generatedIdentity(current.position)) },
        onSave = {
            if (!saving) {
                saving = true
                val name = draft.nameToSave(current.currentName)
                val profile = draft.toProfile(generatedIdentity(current.position))
                scope.launch {
                    whileUnlocked(navController, holder) {
                        withContext(Dispatchers.IO) {
                            val store = WalletStore(session)
                            if (name != current.currentName) store.renameWallet(walletId, name)
                            store.saveWalletProfile(walletId, profile)
                        }
                        navController.popBackStack()
                    }
                }
            }
        },
    )
}

/**
 * The batch style of the selected accounts of wallet [walletId]. DONE applies what was picked and typed in one write. With nothing
 * picked, DONE goes back without writing. RESET STYLE applies the reset to every selected account at once. A route whose accounts
 * do not all exist goes back without saving.
 */
@Composable
private fun StyleAccountsRoute(navController: NavHostController, holder: VaultHolder, walletId: String, indices: List<Int>) {
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

    var edits by remember(walletId, indices) { mutableStateOf(StyleEdits()) }
    var saving by remember(walletId, indices) { mutableStateOf(false) }

    // One write for the whole batch, then back to the wallet, whose detail reads the styles again.
    fun applyChanges(changes: List<StyleChange>) {
        if (saving) return
        saving = true
        scope.launch {
            whileUnlocked(navController, holder) {
                withContext(Dispatchers.IO) { WalletStore(session).changeAccountStyles(walletId, indices, changes) }
                navController.popBackStack()
            }
        }
    }

    StyleAccountsScreen(
        count = indices.size,
        edits = edits,
        onFocus = { field -> edits = edits.focusOn(field) },
        onKey = { c -> if (!saving) edits = edits.typing(c) },
        onBackspace = { if (!saving) edits = edits.backspace() },
        onPickColour = { colour -> edits = edits.pickColour(colour) },
        onPickIcon = { icon -> edits = edits.pickIcon(icon) },
        onClear = { field -> edits = edits.clear(field) },
        onResetStyle = { applyChanges(listOf(StyleChange.Reset)) },
        onSave = {
            if (!saving) {
                val changes = edits.changes()
                if (changes.isEmpty()) navController.popBackStack() else applyChanges(changes)
            }
        },
    )
}
