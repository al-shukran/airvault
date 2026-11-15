package app.lensly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.sign.FactRow
import app.lensly.ui.sign.MonoText
import app.lensly.ui.sign.ProxyFacts
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutline
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurface
import app.lensly.ui.theme.LenslySurfaceContainer

/**
 * The approved contracts: every whitelist entry of the installed catalog, grouped by network. A search field narrows the list by
 * protocol, chain, function, meaning or address. Each entry shows its protocol, a short contract address, its contract in full, its
 * function and selector, its approved meaning, and a Proxy chip with the implementation it is bound to. The list only changes
 * through a signed offline update, so an empty list says so and offers Update catalog, the one way to add an entry. [entries] and
 * [networks] are the catalog as read.
 */
@Composable
fun ApprovedContractsScreen(
    entries: List<WhitelistEntry>,
    networks: List<NetworkEntry>,
    onUpdateCatalog: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var focused by rememberSaveable { mutableStateOf(false) }
    val shown = remember(entries, networks, query) { filterApprovals(entries, networks, query) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = "Approved contracts",
            modifier = modifier,
            onBack = onBack,
            bottomAction = {
                BottomSlot(visible = focused && entries.isNotEmpty()) {
                    SecureKeyboard(
                        onKey = { query += it },
                        onBackspace = { query = query.dropLast(1) },
                        onDone = { focused = false },
                    )
                }
            },
        ) {
            CatalogIntro(
                text = "Calls on these contracts are signed although Lensly cannot decode them. Each one shows its approved " +
                    "meaning before you sign.",
                onLock = onLock,
            )
            if (entries.isEmpty()) {
                Spacer(Modifier.height(LenslySpacing.lg))
                DotHeadline(text = "NO APPROVED CONTRACTS")
                Spacer(Modifier.height(LenslySpacing.sm))
                Text(
                    text = "Contracts are added only through a signed offline update (Update catalog). This prevents anyone from " +
                        "approving a contract on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(LenslySpacing.md))
                PillButton(
                    text = "Update catalog",
                    onClick = onUpdateCatalog,
                    modifier = Modifier.fillMaxWidth(),
                    accent = true,
                )
            } else {
                Spacer(Modifier.height(LenslySpacing.md))
                SearchField(query = query, focused = focused, onFocus = { focused = true })
                if (shown.isEmpty()) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    Text(
                        text = "No approved call matches \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shown.groupBy { it.chainId }.forEach { (chainId, group) ->
                    Spacer(Modifier.height(LenslySpacing.lg))
                    NetworkHeader(network = networks.firstOrNull { it.chainId == chainId }, chainId = chainId)
                    Spacer(Modifier.height(LenslySpacing.sm))
                    group.forEach { entry ->
                        ApprovalCard(entry)
                        Spacer(Modifier.height(LenslySpacing.sm))
                    }
                }
                Spacer(Modifier.height(LenslySpacing.lg))
                PillButton(
                    text = "Update catalog",
                    onClick = onUpdateCatalog,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The one search field. A tap focuses it and shows the in-app keyboard, which types the query; the query shows as typed, with a
 * caret while the field has focus. Nothing is sent anywhere, and the list narrows as the query grows.
 */
@Composable
private fun SearchField(query: String, focused: Boolean, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SEARCH_TAG)
            .clip(shape)
            .background(LenslySurfaceContainer, shape)
            .border(1.dp, LenslyOutlineInteractive, shape)
            .clickable(role = Role.Button, onClick = onFocus)
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            query.isNotEmpty() -> Text(
                text = if (focused) "${query}_" else query,
                style = MaterialTheme.typography.bodyLarge,
                color = LenslyOnSurface,
            )
            focused -> Text(text = "_", style = MaterialTheme.typography.bodyLarge, color = LenslyOnSurface)
            else -> Text(
                text = "Search protocol, chain, function or address",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A network's logo and name, with its chain ID. A chain that is not in the catalog is named by its ID alone. */
@Composable
private fun NetworkHeader(network: NetworkEntry?, chainId: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        ChainIcon(network = network, size = 20.dp)
        Text(
            text = network?.name ?: "Chain $chainId",
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
        )
        PillChip(text = "Chain $chainId")
    }
}

/** One approved call as a card: its function and selector, its contract in full, and its approved meaning. */
@Composable
private fun ApprovalCard(entry: WhitelistEntry) {
    val shape = RoundedCornerShape(LenslyRadius.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LenslySurface)
            .border(1.dp, LenslyOutline, shape)
            .padding(horizontal = LenslySpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = protocolOf(entry).ifEmpty { "Approved call" },
                    style = MaterialTheme.typography.titleSmall,
                    color = LenslyOnSurface,
                )
                AddressText(address = entry.contract, shown = shortAddress(entry.contract), icon = false)
            }
            if (entry.proxy != null) PillChip(text = "Proxy")
        }
        MonoText(text = entry.signature, modifier = Modifier.padding(top = LenslySpacing.xs))
        FactRow(label = "Contract") {
            AddressText(address = entry.contract)
        }
        FactRow(label = "Selector") {
            MonoText(text = entry.selector)
        }
        FactRow(label = "Approved meaning") {
            Text(
                text = entry.meaning,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        entry.proxy?.let { ProxyFacts(it) }
    }
}

/** The test tag of the search field, so a test can type into it. */
const val SEARCH_TAG = "approved-contracts-search"
