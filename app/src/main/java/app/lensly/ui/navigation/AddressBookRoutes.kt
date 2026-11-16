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
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressBookStore
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.PROFILE_TEXT_MAX_LENGTH
import app.lensly.app.VaultHolder
import app.lensly.app.addressBookEntryOf
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
    composable(HiddenRoute.ADDRESS_BOOK) { HiddenBackdrop { AddressBookRoute(navController, holder) } }
    composable(
        HiddenRoute.ADDRESS_BOOK_ADD,
        arguments = listOf(navArgument(HiddenRoute.ADDRESS_ARG) { type = NavType.StringType; defaultValue = "" }),
    ) { entry ->
        val prefilled = entry.arguments?.getString(HiddenRoute.ADDRESS_ARG).orEmpty()
        HiddenBackdrop { AddressBookFormRoute(navController, holder, entryId = null, prefilled = prefilled) }
    }
    composable(
        HiddenRoute.ADDRESS_BOOK_EDIT,
        arguments = listOf(navArgument(HiddenRoute.ENTRY_ID_ARG) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(HiddenRoute.ENTRY_ID_ARG)
        HiddenBackdrop { AddressBookFormRoute(navController, holder, entryId = id, prefilled = "") }
    }
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
        onBack = { navController.popBackStack() },
    )
}

/**
 * The form for entry [entryId], or for a new entry filled with [prefilled] when [entryId] is null. A save checks the entry with
 * [addressBookEntryOf] before it is written. Delete asks first. The form returns to the list once the book has changed.
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
    var focused by remember { mutableStateOf<AddressBookField?>(null) }
    var error by remember { mutableStateOf(if (loaded.isFailure) "The Address Book could not be read." else null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val onKey: (Char) -> Unit = { key ->
        when (focused) {
            AddressBookField.ADDRESS -> if (address.length < ADDRESS_BOOK_ADDRESS_MAX_LENGTH) address += key
            AddressBookField.NAME -> if (name.length < DISPLAY_NAME_MAX_LENGTH) name += key
            AddressBookField.LABEL -> if (label.length < PROFILE_TEXT_MAX_LENGTH) label += key
            null -> Unit
        }
    }
    val onBackspace: () -> Unit = {
        when (focused) {
            AddressBookField.ADDRESS -> address = address.dropLast(1)
            AddressBookField.NAME -> name = name.dropLast(1)
            AddressBookField.LABEL -> label = label.dropLast(1)
            null -> Unit
        }
    }
    val onSave: () -> Unit = {
        scope.launch {
            val next = runCatching { addressBookEntryOf(id, address, name, label, book) }
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
        title = if (entry == null) "Add address" else "Edit address",
        address = address,
        name = name,
        label = label,
        focused = focused,
        onFocus = { focused = it },
        onKey = onKey,
        onBackspace = onBackspace,
        onSave = onSave,
        onBack = { navController.popBackStack() },
        onDelete = if (entry == null) null else ({ confirmDelete = true }),
        error = error,
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this address?") },
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
