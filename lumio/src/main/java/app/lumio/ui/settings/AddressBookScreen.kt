package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.app.AddressBookEntry
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirAddressGrid
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirStatusBand
import app.lumio.ui.shell.LumioPageScreen

/**
 * The Address Book of one paired wallet: each saved name, its label and its address, grouped by the Group each entry has. Names are added and changed
 * on Lensly, so this screen is read only and has no edit action. A pairing from Lensly again replaces the book. With no entry, it says how to add one.
 */
@Composable
fun AddressBookScreen(walletName: String, entries: List<AddressBookEntry>, offline: String?, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    // Named groups come first in alphabetical order, ignoring case, and the entries with no group follow under no header.
    val (named, ungrouped) = entries.partition { it.group.isNotBlank() }
    val groups = named.groupBy { it.group.trim() }.toList().sortedBy { (group, _) -> group.lowercase() }
    // Opened from Settings or from Search, so the back arrow names no place: it is read as Back.
    LumioPageScreen(title = SettingsPage.ADDRESS_BOOK.title, backLabel = null, offline = offline, onBack = onBack, onLock = onLock) {
        ClearAirStatusBand(text = READ_ONLY)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm)) {
                Text(walletName, style = ClearAirType.rowTitle, color = palette.ink)
                if (entries.isEmpty()) {
                    Text(text = "No entries yet. Add them on Lensly, then re-pair.", style = ClearAirType.body, color = palette.ink2)
                }
            }
            groups.forEach { (group, inGroup) ->
                ClearAirEyebrow(text = "$group · ${inGroup.size}", modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
                inGroup.forEach { entry -> AddressEntry(entry) }
            }
            ungrouped.forEach { entry -> AddressEntry(entry) }
        }
    }
}

@Composable
private fun AddressEntry(entry: AddressBookEntry) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)) {
        Text(entry.name, style = ClearAirType.rowTitle, color = palette.ink)
        entry.label?.let { Text(it, style = ClearAirType.body, color = palette.ink2) }
        ClearAirAddressGrid(address = entry.address, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
    }
}

/** The band under the top bar: the book is edited on Lensly, so it cannot be changed here. */
private const val READ_ONLY = "Read only · edited on Lensly"
