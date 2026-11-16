package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.app.AddressBookEntry
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.ui.send.SectionLabel

/** The chains an Address Book entry can be on, in the order the screen lists them. */
private val CHAINS = listOf("Ethereum", "Bitcoin")

/** The chain of an Address Book [address]: an EVM address starts with 0x, and any other is a Bitcoin address. */
private fun chainOf(address: String): String = if (address.startsWith("0x")) "Ethereum" else "Bitcoin"

/**
 * The Address Book of one paired wallet, read only: each saved name, its label and its address, grouped by chain. Lensly is the source of
 * truth, so the screen has no edit action. A pairing from Lensly again replaces the book.
 */
@Composable
fun AddressBookScreen(walletName: String, entries: List<AddressBookEntry>, onBack: () -> Unit) {
    LenslyScreen(title = "ADDRESS BOOK", onBack = onBack) {
        Text(walletName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "Read only. Lensly keeps the Address Book. Pair this wallet again from Lensly to update it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Text(
                text = "No saved addresses.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CHAINS.forEach { chain ->
            val inChain = entries.filter { chainOf(it.address) == chain }
            if (inChain.isNotEmpty()) {
                SectionLabel(chain)
                inChain.forEach { entry -> AddressEntry(entry) }
            }
        }
    }
}

@Composable
private fun AddressEntry(entry: AddressBookEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        entry.label?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        AddressText(address = entry.address, icon = false)
    }
}
