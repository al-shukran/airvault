package app.lumio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressBookEntry
import app.lensly.app.addressChainLabel
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.transactions.AddressInfoSheet
import app.lumio.ui.transactions.monogramOf
import app.lumio.ui.wallets.ROW_DIVIDER_INDENT
import app.lumio.ui.wallets.RowDivider

/** The test tag of the search field. */
const val ADDRESS_SEARCH_TAG = "address-search"

/**
 * The Address Book: a note that it is read only, a search over the names and addresses, then each saved name under the Group it has, with its chain,
 * label and short address. A row opens the address's sheet, which hands over to Lensly with [onEdit], since names are added and changed on Lensly.
 * A pairing from Lensly again replaces the book. With no entry, it says how to add one.
 */
@Composable
fun AddressBookScreen(entries: List<AddressBookEntry>, offline: String?, onEdit: (String) -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf<AddressBookEntry?>(null) }
    val shown = entries.filter { it.matches(query) }
    // Named groups come first in alphabetical order, ignoring case, and the entries with no group follow under no header.
    val (named, ungrouped) = shown.partition { it.group.isNotBlank() }
    val groups = named.groupBy { it.group.trim() }.toList().sortedBy { (group, _) -> group.lowercase() }
    // Opened from Settings or from Search, so the back arrow names no place: it is read as Back.
    LumioPageScreen(
        title = SettingsPage.ADDRESS_BOOK.title,
        backLabel = null,
        offline = offline,
        onBack = onBack,
        onLock = onLock,
        sheet = {
            opened?.let { entry ->
                AddressInfoSheet(
                    address = entry.address,
                    entry = entry,
                    walletName = null,
                    seenIn = null,
                    onEdit = {
                        opened = null
                        onEdit(entry.address)
                    },
                    onAdd = {},
                    onClose = { opened = null },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ClearAirNote(text = READ_ONLY, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
            SearchField(query = query, onQuery = { query = it })
            when {
                entries.isEmpty() -> Text(
                    text = "No entries yet. Add them on Lensly, then re-pair.",
                    modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
                    style = ClearAirType.body,
                    color = palette.ink2,
                )
                shown.isEmpty() -> Text(
                    text = "No entry matches your search.",
                    modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
                    style = ClearAirType.body,
                    color = palette.ink2,
                )
            }
            groups.forEach { (group, inGroup) ->
                ClearAirEyebrow(
                    text = "$group · ${inGroup.size}",
                    modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
                    top = ClearAirSpacing.md,
                )
                EntryRows(inGroup) { opened = it }
            }
            if (ungrouped.isNotEmpty()) {
                if (groups.isNotEmpty()) Spacer(Modifier.height(ClearAirSpacing.md))
                EntryRows(ungrouped) { opened = it }
            }
        }
    }
}

/** The rows of [entries], each with a hairline under the one above it that begins where its text does. */
@Composable
private fun EntryRows(entries: List<AddressBookEntry>, onOpen: (AddressBookEntry) -> Unit) {
    entries.forEachIndexed { index, entry ->
        if (index > 0) RowDivider(indent = ROW_DIVIDER_INDENT)
        ClearAirRow(
            title = entry.name,
            description = entry.subtitle(),
            address = entry.address,
            leading = { ClearAirMonogram(text = monogramOf(entry.name, named = true)) },
            onClick = { onOpen(entry) },
        )
    }
}

/** The search field: a raised capsule with the search mark, in which the owner types a name or part of an address. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .padding(start = ClearAirSpacing.md, end = ClearAirSpacing.md, top = ClearAirSpacing.sm)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(palette.raised)
            .padding(horizontal = ClearAirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.SEARCH, color = palette.ink2)
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = "Search names and addresses", style = ClearAirType.body, color = palette.ink2, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = ClearAirType.body.copy(color = palette.ink),
                cursorBrush = SolidColor(palette.ink),
                modifier = Modifier.fillMaxWidth().testTag(ADDRESS_SEARCH_TAG),
            )
        }
    }
}

/** Whether the name, label or address of this entry holds [query], ignoring case and the space around it. An empty query matches every entry. */
private fun AddressBookEntry.matches(query: String): Boolean {
    val text = query.trim()
    return text.isEmpty() || listOfNotNull(name, label, address).any { it.contains(text, ignoreCase = true) }
}

/**
 * The line under a name: the chain the address is on (a testnet4 address names its network) and the label. An EVM address is one account on every EVM
 * network, so it names the family, Ethereum. The address is the row's last line.
 */
private fun AddressBookEntry.subtitle(): String? = listOfNotNull(addressChainLabel(address), label).joinToString(" · ").ifEmpty { null }

/** The note under the top bar: the book is edited on Lensly, so it cannot be changed here. */
private const val READ_ONLY = "Read only · edited on Lensly"
