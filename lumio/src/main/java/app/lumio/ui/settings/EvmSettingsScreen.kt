package app.lumio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.ListRow
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SettingsItem
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.components.SettingsSwitchRow
import app.lensly.ui.components.TextAction
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings


/**
 * The Ethereum section of the settings: how many addresses each wallet shows, which chains are read, the RPC address of each chain, and the
 * custom chains. [onEnabled], [onSetRpc], [onResetRpc], [onRemoveCustom] and [onAddressCount] report a change to the caller, which writes it. A
 * change that is refused reports its words through the callback it was given, and the dialog stays open. [notice] shows a refusal that is not
 * tied to a dialog. [busy] holds every change until the last one has finished. Each chain is a switch row. [onAddNetwork] opens the page that
 * adds a custom network, and adding one is a row too.
 */
@Composable
fun EvmSettingsSection(
    settings: EvmSettings,
    chains: List<EvmChain>,
    busy: Boolean,
    notice: String?,
    onAddressCount: (Int) -> Unit,
    onEnabled: (chainId: Long, on: Boolean) -> Unit,
    onSetRpc: (chainId: Long, raw: String, onDone: (String?) -> Unit) -> Unit,
    onResetRpc: (chainId: Long) -> Unit,
    onRemoveCustom: (chainId: Long) -> Unit,
    onAddNetwork: () -> Unit,
    onTokens: (chainId: Long) -> Unit = {},
) {
    var editing by remember { mutableStateOf<EvmChain?>(null) }
    notice?.let {
        InlineBanner(message = it, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.sm))
    }
    SectionLabel("Addresses per Wallet")
    AddressCountRow(
        count = settings.addressCount,
        busy = busy,
        onCount = onAddressCount,
    )
    SectionLabel("Chains")
    chains.forEach { chain ->
        ChainRow(
            chain = chain,
            settings = settings,
            busy = busy,
            onEnabled = { on -> onEnabled(chain.chainId, on) },
            onEdit = { editing = chain },
            onTokens = { onTokens(chain.chainId) },
            onReset = { onResetRpc(chain.chainId) },
            onRemove = { onRemoveCustom(chain.chainId) },
        )
    }
    SettingsRow(
        title = "Add Custom Network",
        description = "An EVM network the registry does not list",
        onClick = onAddNetwork,
        enabled = !busy,
    )
    editing?.let { chain ->
        RpcDialog(
            chain = chain,
            initial = settings.rpc[chain.chainId].orEmpty(),
            onSave = { raw, onDone -> onSetRpc(chain.chainId, raw, onDone) },
            onDismiss = { editing = null },
        )
    }
}

/** A label that opens a block of the Ethereum section, such as the chains. */
@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(LenslySpacing.md))
    WalletEyebrow(text)
    Spacer(Modifier.height(LenslySpacing.xs))
}

/** How many addresses each wallet shows, with one step down and one step up. The steps stop at the limits. */
@Composable
private fun AddressCountRow(count: Int, busy: Boolean, onCount: (Int) -> Unit) {
    SettingsItem {
        ListRow(onClick = null) {
            Text(
                "$count shown",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = { onCount(count - 1) },
                modifier = Modifier.semantics { contentDescription = "Show fewer addresses" },
                enabled = !busy && count > 1,
            ) {
                Text("\u2212", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(
                onClick = { onCount(count + 1) },
                modifier = Modifier.semantics { contentDescription = "Show more addresses" },
                enabled = !busy && count < EvmSettings.MAX_ADDRESS_COUNT,
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * One chain: a switch that turns its reads on or off, its RPC address, and the actions that change that address or remove the chain. Only
 * a chain the owner added can be removed, and only an address the owner set can be reset.
 */
@Composable
private fun ChainRow(
    chain: EvmChain,
    settings: EvmSettings,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTokens: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
) {
    val custom = settings.custom.any { it.chainId == chain.chainId }
    val overridden = settings.rpc.containsKey(chain.chainId)
    SettingsSwitchRow(
        title = chain.name,
        description = "Chain ${chain.chainId} · ${chain.symbol}",
        checked = chain.chainId in settings.enabled,
        onCheckedChange = onEnabled,
        enabled = !busy,
        footer = {
            if (chain.testnet) PillChip(text = "Testnet")
            Text(
                "RPC: ${settings.rpc[chain.chainId] ?: "public endpoints"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(text = "Tokens", onClick = onTokens, enabled = !busy)
                TextAction(text = "Edit RPC", onClick = onEdit, enabled = !busy)
                if (overridden) TextAction(text = "Reset", onClick = onReset, enabled = !busy)
                if (custom) TextAction(text = "Remove", onClick = onRemove, enabled = !busy, destructive = true)
            }
            Spacer(Modifier.height(LenslySpacing.xs))
        },
    )
}

/** Sets the RPC address of [chain]. The address is checked by the caller, and a refusal keeps the dialog open with its words. */
@Composable
private fun RpcDialog(
    chain: EvmChain,
    initial: String,
    onSave: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RPC for ${chain.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("RPC Address (https)") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Lumio checks that the endpoint answers for chain ${chain.chainId} before it applies.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    onSave(text) { result ->
                        saving = false
                        if (result == null) onDismiss() else error = result
                    }
                },
            ) {
                Text("Check and Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
