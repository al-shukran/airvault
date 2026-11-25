package app.lumio.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.app.ADDRESS_NOTES_MAX_LENGTH
import app.lensly.app.AddressBookEntry
import app.lensly.app.plainName
import app.lensly.app.plainNameOf
import app.lensly.app.plainNameOrNull
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirAddressGrid
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.rememberCopyAction

/** How Lumio says it knows a saved address: its group when the owner gave one, otherwise that the entry came from Lensly. */
internal fun addressBookSourceOf(entry: AddressBookEntry): String =
    if (entry.group.isEmpty()) "Address book · from Lensly" else "Address book · Group ${entry.group}"

/**
 * The address-book sheet of an address on a transaction page. Its name, label and notes are shown as plain text. It is read only: a known address shows what Lensly holds for it, and an unknown one says
 * so and how often it has come up in the wallet's history. [entry] is null for an address that is not in the address book. The capsule copies the
 * address, and the text action hands over to Lensly: [onEdit] for a known entry and [onAdd] for an unknown one.
 */
@Composable
fun AddressInfoSheet(
    address: String,
    entry: AddressBookEntry?,
    walletName: String?,
    seenIn: Int?,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val copy = rememberCopyAction(value = address, label = "Address")
    ClearAirSheet(visible = true, onClose = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Monogram(
                    text = monogramOf(entry?.plainName().orEmpty(), named = entry != null),
                    known = entry != null,
                    size = MONOGRAM_BIG,
                )
                Spacer(Modifier.width(ClearAirSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry?.plainName() ?: "Not in Address Book", style = ClearAirType.titleL, color = palette.ink)
                    val summary = when {
                        entry != null -> addressBookSourceOf(entry)
                        seenIn != null && walletName != null -> "Seen in $seenIn ${if (seenIn == 1) "transaction" else "transactions"} with $walletName"
                        else -> "Tap to add it on Lensly"
                    }
                    Text(text = summary, style = ClearAirType.body, color = palette.ink2)
                }
            }
            entry?.group?.takeIf { it.isNotEmpty() }?.let { KeyValue(label = "Group", value = it) }
            plainNameOrNull(entry?.label)?.let { KeyValue(label = "Label", value = it) }
            plainNameOf(entry?.notes.orEmpty(), ADDRESS_NOTES_MAX_LENGTH).takeIf { it.isNotEmpty() }?.let { KeyValue(label = "Notes", value = it) }
            ClearAirEyebrow(text = "Address", modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
            ClearAirAddressGrid(address = address, modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter))
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirNote(text = "Address-book entries are made on Lensly. Add or edit it there, then re-pair to update Lumio.")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ClearAirPrimaryButton(text = if (copy.copied) "Address Copied" else "Copy Address", onClick = copy.copy)
                ClearAirTextAction(
                    text = if (entry != null) "Edit on Lensly" else "Add on Lensly",
                    onClick = if (entry != null) onEdit else onAdd,
                )
            }
        }
    }
}
