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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.lensly.app.AddressBookEntry
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import kotlinx.coroutines.delay

/** How long the capsule says the address was copied. */
private const val COPIED_MS = 1500L

/**
 * The address-book sheet of an address on a transaction page. It is read only: a known address shows what Lensly holds for it, and an unknown one says
 * so and how often it has come up in the wallet's history. The two actions hand over to Lensly: [onEdit] for a known entry and [onAdd] for an unknown
 * one. [entry] is null for an address that is not in the address book.
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
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    ClearAirSheet(visible = true, onClose = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Monogram(text = monogramOf(entry?.name.orEmpty(), named = entry != null), known = entry != null)
                Spacer(Modifier.width(ClearAirSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry?.name ?: "Not in Address Book", style = ClearAirType.titleL, color = palette.ink)
                    val summary = when {
                        entry != null -> "Address book · from Lensly"
                        seenIn != null && walletName != null -> "Seen in $seenIn ${if (seenIn == 1) "transaction" else "transactions"} with $walletName"
                        else -> "Tap to add it on Lensly"
                    }
                    Text(text = summary, style = ClearAirType.body, color = palette.ink2)
                }
            }
            entry?.label?.let { KeyValue(label = "Label", value = it) }
            entry?.notes?.let { KeyValue(label = "Notes", value = it) }
            ClearAirEyebrow(text = "Address", modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
            Text(
                text = groupAddress(address),
                style = ClearAirType.address,
                color = palette.ink,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
            )
            Spacer(Modifier.height(ClearAirSpacing.sm))
            ClearAirNote(text = "Address-book entries are made on Lensly. Add or edit it there, then re-pair to update Lumio.")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearAirPrimaryButton(
                    text = if (copied) "Address copied" else "Copy Address",
                    onClick = {
                        clipboard.setText(AnnotatedString(address))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(ClearAirSpacing.md))
                if (entry != null) {
                    ClearAirTextAction(text = "Edit on Lensly", onClick = onEdit)
                } else {
                    ClearAirTextAction(text = "Add on Lensly", onClick = onAdd)
                }
            }
        }
    }
}
