package app.lensly.ui.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import app.airvault.core.model.ChainId
import app.lensly.app.ADDRESS_NOTES_MAX_LENGTH
import app.lensly.app.ADDRESS_NOT_VALID_MESSAGE
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressBookStore
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.InternalAccount
import app.lensly.app.PROFILE_TEXT_MAX_LENGTH
import app.lensly.app.ScanTarget
import app.lensly.app.VaultHolder
import app.lensly.app.WalletStore
import app.lensly.app.addressBookEntryFor
import app.lensly.app.addressBookEntryOf
import app.lensly.app.chainOfAddress
import app.lensly.app.internalAccountsOf
import app.lensly.app.scanTargetOf
import app.lensly.core.vault.VaultSession
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.settings.ADDRESS_BOOK_ADDRESS_MAX_LENGTH
import app.lensly.ui.settings.AddressBookField
import app.lensly.ui.settings.AddressBookFormScreen
import app.lensly.ui.settings.AddressBookScreen
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Address Book in the hidden graph: the list, and the form that adds an entry or edits one. Reached from Settings and from a record. */
internal fun NavGraphBuilder.addressBookRoutes(navController: NavHostController, holder: VaultHolder) {
    vaultComposable(HiddenRoute.ADDRESS_BOOK) { HiddenBackdrop { AddressBookRoute(navController, holder) } }
    vaultComposable(HiddenRoute.ADDRESS_BOOK_SCAN) { HiddenBackdrop { AddressBookScanRoute(navController, holder) } }
    vaultComposable(
        HiddenRoute.ADDRESS_BOOK_ADD,
        arguments = listOf(navArgument(HiddenRoute.ADDRESS_ARG) { type = NavType.StringType; defaultValue = "" }),
    ) { entry ->
        val prefilled = entry.arguments?.getString(HiddenRoute.ADDRESS_ARG).orEmpty()
        HiddenBackdrop { AddressBookFormRoute(navController, holder, entryId = null, prefilled = prefilled) }
    }
    vaultComposable(
        HiddenRoute.ADDRESS_BOOK_EDIT,
        arguments = listOf(navArgument(HiddenRoute.ENTRY_ID_ARG) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(HiddenRoute.ENTRY_ID_ARG)
        HiddenBackdrop { AddressBookFormRoute(navController, holder, entryId = id, prefilled = "") }
    }
}

/**
 * Opens the Address Book for [address] on [chain], from a record's sheet: its entry to edit when [book] has one, or a new entry started with
 * the address. The owner types a name, a label and notes, and never the address.
 */
internal fun NavHostController.openAddressBookFor(book: List<AddressBookEntry>, address: String, chain: ChainId) {
    val entry = addressBookEntryFor(address, chain, book)
    navigate(if (entry != null) HiddenRoute.addressBookEdit(entry.id) else HiddenRoute.addressBookAdd(address))
}

/**
 * The Address Book, read again each time the screen is shown, so an entry saved on the form shows on the record it came from. A damaged
 * book reads as empty, as a review does. Null until the first read.
 */
@Composable
internal fun rememberAddressBook(navController: NavHostController, session: VaultSession): List<AddressBookEntry>? {
    val shown by navController.currentBackStackEntryAsState()
    val read by produceState<List<AddressBookEntry>?>(initialValue = null, session, shown) {
        value = withContext(Dispatchers.IO) { runCatching { AddressBookStore(session).load() }.getOrDefault(emptyList()) }
    }
    return read
}

/**
 * The accounts of every wallet of the vault on every chain, read again each time the screen is shown, so an account renamed or added on
 * another screen shows here. [signingWalletId] is the wallet the screen is for, or null for every wallet. Null until the first read.
 */
@Composable
internal fun rememberInternalAccounts(
    navController: NavHostController,
    session: VaultSession,
    signingWalletId: String?,
): Map<String, InternalAccount>? {
    val shown by navController.currentBackStackEntryAsState()
    val read by produceState<Map<String, InternalAccount>?>(initialValue = null, session, signingWalletId, shown) {
        value = withContext(Dispatchers.IO) { runCatching { internalAccountsOf(WalletStore(session), signingWalletId) }.getOrDefault(emptyMap()) }
    }
    return read
}

@Composable
private fun AddressBookRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    // Read again each time the screen is shown, so an entry saved or deleted on the form shows here.
    val shown by navController.currentBackStackEntryAsState()
    val read by produceState<Result<List<AddressBookEntry>>?>(initialValue = null, session, shown) {
        value = withContext(Dispatchers.IO) { runCatching { AddressBookStore(session).load() } }
    }
    val loaded = read ?: return
    AddressBookScreen(
        entries = loaded.getOrDefault(emptyList()),
        error = if (loaded.isFailure) "The Address Book could not be read." else null,
        onOpen = { navController.navigate(HiddenRoute.addressBookEdit(it.id)) },
        onAdd = { navController.navigate(HiddenRoute.addressBookAdd()) },
        onScan = { navController.navigate(HiddenRoute.ADDRESS_BOOK_SCAN) },
        onBack = { navController.popBackStack() },
    )
}

/** The hint under the scanner, until a code is read. */
private const val SCAN_HINT = "Hold the camera over the code that Lumio shows."

/**
 * Add by Scanning: the camera reads an address that Lumio shows. Its entry opens to edit when the book holds the address, and otherwise a new
 * entry opens with it, so the owner only names it. A code that names no address the book takes is refused in the status line, and the scan
 * goes on. The entry replaces the scanner in the back stack, so Back from it returns to the list.
 */
@Composable
private fun AddressBookScanRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val book = rememberAddressBook(navController, session) ?: return
    var status by remember { mutableStateOf(SCAN_HINT) }
    val openEntry: (String) -> Unit = { route ->
        navController.navigate(route) { popUpTo(HiddenRoute.ADDRESS_BOOK_SCAN) { inclusive = true } }
    }
    ScanScreen(
        title = "Add by Scanning",
        progress = null,
        statusText = status,
        onText = { text ->
            when (val target = scanTargetOf(text, book)) {
                null -> status = ADDRESS_NOT_VALID_MESSAGE
                is ScanTarget.Existing -> openEntry(HiddenRoute.addressBookEdit(target.entry.id))
                is ScanTarget.New -> openEntry(HiddenRoute.addressBookAdd(target.address))
            }
        },
        onCancel = { navController.popBackStack() },
    )
}

/**
 * The form for entry [entryId], or for an address [prefilled] from a record. An address the book already holds opens that entry to edit, so
 * a second entry is never made for it. A save checks the entry with [addressBookEntryOf] before it is written. Delete asks first. The form
 * returns to the list once the book has changed.
 */
@Composable
private fun AddressBookFormRoute(navController: NavHostController, holder: VaultHolder, entryId: String?, prefilled: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val read by produceState<Result<List<AddressBookEntry>>?>(initialValue = null, session) {
        value = withContext(Dispatchers.IO) { runCatching { AddressBookStore(session).load() } }
    }
    val loaded = read ?: return
    val book = loaded.getOrDefault(emptyList())
    val entry = entryId?.let { id -> book.firstOrNull { it.id == id } }
        ?: prefilled.takeIf { it.isNotEmpty() }?.let { typed ->
            chainOfAddress(typed)?.let { chain -> addressBookEntryFor(typed, chain, book) }
        }
    if (entryId != null && entry == null && loaded.isSuccess) {
        // The entry was removed on another screen, so there is nothing to edit.
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val scope = rememberCoroutineScope()
    val id by rememberSaveable(entryId) { mutableStateOf(entry?.id ?: UUID.randomUUID().toString()) }
    var address by rememberSaveable(entryId) { mutableStateOf(entry?.address ?: prefilled) }
    var name by rememberSaveable(entryId) { mutableStateOf(entry?.name.orEmpty()) }
    var label by rememberSaveable(entryId) { mutableStateOf(entry?.label.orEmpty()) }
    var notes by rememberSaveable(entryId) { mutableStateOf(entry?.notes.orEmpty()) }
    var focused by remember { mutableStateOf<AddressBookField?>(null) }
    var error by remember { mutableStateOf(if (loaded.isFailure) "The Address Book could not be read." else null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The address is the entry's key. It is typed only for a new entry the owner starts from the list.
    val addressFixed = entry != null || prefilled.isNotEmpty()

    val onKey: (Char) -> Unit = { key ->
        when (focused) {
            AddressBookField.ADDRESS -> if (!addressFixed && address.length < ADDRESS_BOOK_ADDRESS_MAX_LENGTH) address += key
            AddressBookField.NAME -> if (name.length < DISPLAY_NAME_MAX_LENGTH) name += key
            AddressBookField.LABEL -> if (label.length < PROFILE_TEXT_MAX_LENGTH) label += key
            AddressBookField.NOTES -> if (notes.length < ADDRESS_NOTES_MAX_LENGTH) notes += key
            null -> Unit
        }
    }
    val onBackspace: () -> Unit = {
        when (focused) {
            AddressBookField.ADDRESS -> if (!addressFixed) address = address.dropLast(1)
            AddressBookField.NAME -> name = name.dropLast(1)
            AddressBookField.LABEL -> label = label.dropLast(1)
            AddressBookField.NOTES -> notes = notes.dropLast(1)
            null -> Unit
        }
    }
    val onSave: () -> Unit = {
        scope.launch {
            val next = runCatching { addressBookEntryOf(id, address, name, label, book, notes) }
                .getOrElse {
                    error = it.message
                    return@launch
                }
            val failed = withContext(Dispatchers.IO) { runCatching { AddressBookStore(session).put(next) }.isFailure }
            if (failed) error = "The Address Book could not be saved." else navController.popBackStack()
        }
    }
    val onDelete: () -> Unit = {
        scope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching { AddressBookStore(session).delete(id) }.isFailure }
            if (failed) error = "The Address Book could not be saved." else navController.popBackStack()
        }
    }

    AddressBookFormScreen(
        title = if (entry == null) "Add Address" else "Edit Address",
        address = address,
        name = name,
        label = label,
        notes = notes,
        focused = focused,
        onFocus = { focused = it },
        onKey = onKey,
        onBackspace = onBackspace,
        onSave = onSave,
        onBack = { navController.popBackStack() },
        addressFixed = addressFixed,
        onDelete = if (entry == null) null else ({ confirmDelete = true }),
        error = error,
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete This Address?") },
            text = { Text("It is removed from the Address Book on this device. Review Records are not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
