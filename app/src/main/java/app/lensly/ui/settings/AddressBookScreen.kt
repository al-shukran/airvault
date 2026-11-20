package app.lensly.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.app.AddressBookEntry
import app.lensly.app.export.PairingCodec
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.ListRow
import app.lensly.ui.components.RowArrow
import app.lensly.ui.components.SettingsIsland
import app.lensly.ui.sign.MutedText
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle

/**
 * The Address Book: each saved address with its name and label, by name. A tap opens the entry to edit or delete it, and ADD opens the form
 * for a new one. The book is for display only, and no signing rule reads it. [error] is shown when the book could not be read.
 */
@Composable
fun AddressBookScreen(
    entries: List<AddressBookEntry>,
    onOpen: (AddressBookEntry) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    LenslyScreen(
        title = "Address Book",
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            WalletPill(text = "Add Address", onClick = onAdd, modifier = Modifier.fillMaxWidth(), style = WalletPillStyle.Primary)
        },
    ) {
        if (error != null) {
            InlineBanner(message = error, tone = BannerTone.Error)
            Spacer(Modifier.height(LenslySpacing.md))
        }
        if (entries.isEmpty() && error == null) {
            EmptyState(
                icon = Icons.Filled.Person,
                title = "No Saved Addresses",
                body = "Add an address here, or from a Review Record in the history.",
            )
        }
        if (entries.size > PairingCodec.MAX_ADDRESS_BOOK) {
            val left = entries.size - PairingCodec.MAX_ADDRESS_BOOK
            MutedText("Lumio pairs the first ${PairingCodec.MAX_ADDRESS_BOOK} entries, in the order they were added. The other $left are not sent.")
            Spacer(Modifier.height(LenslySpacing.md))
        }
        if (entries.isNotEmpty()) {
            SettingsIsland("Saved Addresses") {
                entries.sortedBy { it.name.lowercase() }.forEachIndexed { index, entry ->
                    if (index > 0) IslandDivider()
                    ListRow(onClick = { onOpen(entry) }) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = entry.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            entry.label?.let { MutedText(it) }
                            AddressText(address = entry.address, icon = false)
                        }
                        RowArrow()
                    }
                }
            }
        }
    }
}
