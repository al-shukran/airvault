package app.lumio.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressChain
import app.lensly.app.addressKeyOf
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LumenMono
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTextAction

/** The test tag of the name field of the address-book sheet. */
const val ADDRESS_NAME_TAG = "address-name"

/**
 * The address-book sheet (section 2.1, Transaction): it names [address] for the owner, or renames it, and Remove drops the entry when there is one. Save is
 * the primary action, and a blank name cannot be saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookSheet(
    address: String,
    entry: AddressBookEntry?,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(address) { mutableStateOf(entry?.name.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = LenslySpacing.md).padding(bottom = LenslySpacing.lg)) {
            Text(text = "Address Book", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = groupAddress(address), style = LumenMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(LenslySpacing.sm))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ADDRESS_NAME_TAG),
            )
            Spacer(Modifier.height(LenslySpacing.md))
            LumioPrimaryButton(text = "Save", onClick = { onSave(name.trim()) }, enabled = name.isNotBlank())
            if (entry != null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LumioTextAction(text = "Remove", onClick = onRemove)
                }
            }
        }
    }
}

/**
 * The Address Book of a wallet after the owner names [address] on [chain] as [name]. An entry that exists keeps its ID and its label, and a new one takes
 * [newId]. The other entries keep their order.
 */
internal fun addressBookWith(
    book: List<AddressBookEntry>,
    address: String,
    chain: AddressChain,
    name: String,
    newId: String,
): List<AddressBookEntry> {
    val key = addressKeyOf(address)
    val existing = book.firstOrNull { addressKeyOf(it.address) == key && it.chain == chain }
    val entry = AddressBookEntry(id = existing?.id ?: newId, address = address, name = name, label = existing?.label, notes = existing?.notes)
    return if (existing == null) book + entry else book.map { if (it.id == existing.id) entry else it }
}

/** The Address Book of a wallet without the entry of [address] on [chain]. */
internal fun addressBookWithout(book: List<AddressBookEntry>, address: String, chain: AddressChain): List<AddressBookEntry> {
    val key = addressKeyOf(address)
    return book.filterNot { addressKeyOf(it.address) == key && it.chain == chain }
}
