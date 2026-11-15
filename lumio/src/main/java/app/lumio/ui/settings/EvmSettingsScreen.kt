package app.lumio.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lumio.evm.CustomChainInput
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings

/**
 * The Ethereum section of the settings: how many addresses each wallet shows, which chains are read, the RPC address of each chain, and the
 * custom chains. [onEnabled], [onSetRpc], [onResetRpc], [onRemoveCustom], [onAddCustom] and [onAddressCount] report a change to the caller,
 * which writes it. A change that is refused reports its words through the callback it was given, and the dialog stays open. [notice] shows a
 * refusal that is not tied to a dialog. [busy] holds every change until the last one has finished.
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
    onAddCustom: (CustomChainInput, onDone: (String?) -> Unit) -> Unit,
) {
    var editing by remember { mutableStateOf<EvmChain?>(null) }
    var adding by remember { mutableStateOf(false) }
    notice?.let {
        InlineBanner(message = it, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.sm))
    }
    SectionLabel("Addresses per wallet")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        PillButton(
            text = "-",
            onClick = { onAddressCount(settings.addressCount - 1) },
            enabled = !busy && settings.addressCount > 1,
        )
        Text(
            "${settings.addressCount}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PillButton(
            text = "+",
            onClick = { onAddressCount(settings.addressCount + 1) },
            enabled = !busy && settings.addressCount < EvmSettings.MAX_ADDRESS_COUNT,
        )
    }
    SectionLabel("Chains")
    chains.forEach { chain ->
        ChainRow(
            chain = chain,
            settings = settings,
            busy = busy,
            onEnabled = { on -> onEnabled(chain.chainId, on) },
            onEdit = { editing = chain },
            onReset = { onResetRpc(chain.chainId) },
            onRemove = { onRemoveCustom(chain.chainId) },
        )
        Spacer(Modifier.height(LenslySpacing.sm))
    }
    PillButton(
        text = "Add custom chain",
        onClick = { adding = true },
        modifier = Modifier.fillMaxWidth(),
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
    if (adding) {
        AddChainDialog(onAdd = onAddCustom, onDismiss = { adding = false })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(LenslySpacing.md))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(LenslySpacing.xs))
}

@Composable
private fun ChainRow(
    chain: EvmChain,
    settings: EvmSettings,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
) {
    val custom = settings.custom.any { it.chainId == chain.chainId }
    val overridden = settings.rpc.containsKey(chain.chainId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(LenslyRadius.md))
            .padding(LenslySpacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(chain.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Chain ${chain.chainId} · ${chain.symbol}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chain.testnet) PillChip(text = "Testnet")
            }
            Switch(
                checked = chain.chainId in settings.enabled,
                onCheckedChange = onEnabled,
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            "RPC: ${settings.rpc[chain.chainId] ?: "public endpoints"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            PillButton(text = "Edit RPC", onClick = onEdit, enabled = !busy)
            if (overridden) PillButton(text = "Reset", onClick = onReset, enabled = !busy)
            if (custom) PillButton(text = "Remove", onClick = onRemove, enabled = !busy)
        }
    }
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
                    label = { Text("RPC address (https)") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Lumio checks that the endpoint answers for chain ${chain.chainId} before it saves.",
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
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Adds a chain the registry does not list. Every field is checked by the caller, and a refusal keeps the dialog open with its words. */
@Composable
private fun AddChainDialog(
    onAdd: (CustomChainInput, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var chainId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var rpc by remember { mutableStateOf("") }
    var testnet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a chain") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
            ) {
                Field("Chain ID", chainId, enabled = !saving, keyboard = KeyboardType.Number) { chainId = it }
                Field("Name", name, enabled = !saving) { name = it }
                Field("Native symbol", symbol, enabled = !saving) { symbol = it }
                Field("RPC address (https)", rpc, enabled = !saving) { rpc = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
                    Switch(checked = testnet, onCheckedChange = { testnet = it }, enabled = !saving)
                    Text("Testnet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    val input = CustomChainInput(chainId, name, symbol, rpc, testnet)
                    onAdd(input) { result ->
                        saving = false
                        if (result == null) onDismiss() else error = result
                    }
                },
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
