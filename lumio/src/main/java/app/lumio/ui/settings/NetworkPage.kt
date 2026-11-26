package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lumio.ui.kit.ClearAirDraggableRows
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirNumberedRow
import app.lumio.ui.kit.ClearAirRadio
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction

/**
 * The most the Add action lays out tall: the 56 dp field less its 6 dp padding and its 2 dp edge. The mock's Add is a short text, so the field stays 56 dp, and the
 * action's 48 dp press still reaches past this box.
 */
private val CUSTOM_RPC_ACTION_HEIGHT = 40.dp

/** The mock sets this one text action inside a field at 14 px, where the others of the kit are 15. */
private val ADD_ACTION = ClearAirType.rowTitle.copy(fontSize = 14.sp)

/**
 * The Network page (B4) of one chain. The built-in RPC row shows [builtInUrl], the address the built-in RPC reads from, and it cannot be deleted. The owner's own
 * addresses follow, one radio row each: the one in use is filled, shows [inUseLine], and tapping a row makes it the one in use at once. [custom] lists
 * those addresses and [active] is the one in use, or null for the built-in address. A new address is typed in the field and checked when the owner presses
 * Add or leaves the field, and a refusal shows under the field with the typed address kept. [providers] are the data providers that serve the chain, in
 * the order they are asked, and [onReorder] gets their new order when the owner drags one. [onTokens] opens the chain's tokens, and [onRemoveNetwork]
 * drops a chain the owner added, which is null for a built-in chain. [busy] holds the page while a change runs.
 */
@Composable
fun NetworkPage(
    chainName: String,
    chainLine: String,
    builtInUrl: String?,
    custom: List<String>,
    active: String?,
    inUseLine: String,
    providers: List<ProviderRow>,
    busy: Boolean,
    notice: String?,
    offline: String?,
    onUseBuiltIn: () -> Unit,
    onUseCustom: (String) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onTokens: () -> Unit = {},
    onRemoveNetwork: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }
    // The typed address clears once the list or the address in use changes, which a saved address does.
    LaunchedEffect(custom, active) { draft = "" }
    fun addDraft() {
        if (!busy && draft.isNotBlank()) onAddCustom(draft.trim())
    }
    SettingsFrame(title = chainName, offline = offline, onBack = onBack, onLock = onLock, backLabel = "Networks") {
        ClearAirNote(text = chainLine, modifier = Modifier.padding(top = ClearAirSpacing.md))
        SettingsGroup(title = "Default RPC") {
            ClearAirRow(
                title = builtInUrl ?: "Built-in RPC",
                tag = "Built In",
                description = "Can't be deleted",
                modifier = Modifier.selectable(selected = active == null, enabled = !busy, role = Role.RadioButton, onClick = onUseBuiltIn),
                trailing = { ClearAirRadio(selected = active == null) },
            )
        }
        if (custom.isNotEmpty()) {
            SettingsGroup(title = "Custom RPCs") {
                custom.forEach { url ->
                    ClearAirRow(
                        title = url,
                        description = if (url == active) inUseLine else null,
                        modifier = Modifier.selectable(
                            selected = url == active,
                            enabled = !busy,
                            role = Role.RadioButton,
                            onClick = { onUseCustom(url) },
                        ),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ClearAirTextAction(text = "Remove", onClick = { onRemoveCustom(url) }, enabled = !busy)
                                Spacer(Modifier.width(ClearAirSpacing.sm))
                                ClearAirRadio(selected = url == active)
                            }
                        },
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
            ClearAirField(
                label = "Add Custom RPC",
                value = draft,
                onValueChange = { draft = it },
                placeholder = "https://",
                keyboard = KeyboardType.Uri,
                monospace = true,
                error = notice,
                onCommit = { addDraft() },
                trailing = { ClearAirTextAction(text = "Add", onClick = { addDraft() }, enabled = !busy && draft.isNotBlank(), style = ADD_ACTION) },
                inputTag = "custom-rpc",
                trailingHeight = CUSTOM_RPC_ACTION_HEIGHT,
            )
        }
        if (providers.isNotEmpty()) {
            SettingsGroup(title = "Data Provider Order") {
                ClearAirDraggableRows(rows = providers, key = { it.id }, onReorder = onReorder) { index, provider, handle ->
                    ClearAirNumberedRow(
                        number = index + 1,
                        title = providerName(provider.id),
                        description = providerStatusLine(provider.status),
                        dragHandle = handle,
                    )
                }
            }
        }
        SettingsGroup(title = "Network") {
            ClearAirRow(title = "Tokens", description = "The tokens Lumio lists on $chainName", onClick = onTokens)
            if (onRemoveNetwork != null) {
                ClearAirRow(title = "Remove Network", description = "Drops this network you added", onClick = if (busy) null else onRemoveNetwork, destructive = true)
            }
        }
    }
}
