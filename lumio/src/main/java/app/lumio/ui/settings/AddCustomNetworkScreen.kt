package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.evm.CustomChainInput
import app.lumio.evm.CustomNetworkField
import app.lumio.evm.EvmRefusal
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirSwitchRow
import app.lumio.ui.kit.ClearAirTone

/**
 * The Add Custom Network page: an EVM network the registry does not list, by its name, RPC address, chain ID and symbol, with an optional explorer
 * and a switch for a test network. The screen keeps no state of its own: [input] and [refusal] come from the caller, and each change goes back to it.
 * A refusal shows under the field it concerns, and a refusal that concerns no one field shows under the button. [busy] holds the form while the
 * endpoint is checked, and [onAdd] is pressed to check the network, so no error shows while the owner is still typing.
 */
@Composable
fun AddCustomNetworkScreen(
    input: CustomChainInput,
    refusal: EvmRefusal?,
    busy: Boolean,
    offline: String?,
    onInput: (CustomChainInput) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    SettingsFrame(title = "Add Custom Network", offline = offline, onBack = onBack, onLock = onLock, backLabel = "Networks") {
        Column(modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
            ClearAirField(
                label = "Name",
                value = input.name,
                onValueChange = { onInput(input.copy(name = it)) },
                placeholder = "Example Chain",
                error = errorOf(refusal, CustomNetworkField.NAME),
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirField(
                label = "RPC URL",
                value = input.rpc,
                onValueChange = { onInput(input.copy(rpc = it)) },
                placeholder = "https://",
                keyboard = KeyboardType.Uri,
                monospace = true,
                error = errorOf(refusal, CustomNetworkField.RPC),
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirField(
                label = "Chain ID",
                value = input.chainId,
                onValueChange = { onInput(input.copy(chainId = it)) },
                placeholder = "e.g. 10143",
                keyboard = KeyboardType.Number,
                error = errorOf(refusal, CustomNetworkField.CHAIN_ID),
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirField(
                label = "Symbol",
                value = input.symbol,
                onValueChange = { onInput(input.copy(symbol = it)) },
                placeholder = "ETH",
                error = errorOf(refusal, CustomNetworkField.SYMBOL),
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirField(
                label = "Explorer (Optional)",
                value = input.explorer,
                onValueChange = { onInput(input.copy(explorer = it)) },
                placeholder = "https://",
                keyboard = KeyboardType.Uri,
                monospace = true,
                error = errorOf(refusal, CustomNetworkField.EXPLORER),
            )
        }
        ClearAirSwitchRow(
            title = "Test Network",
            description = "Lists it with the test networks",
            checked = input.testnet,
            onCheckedChange = { onInput(input.copy(testnet = it)) },
            enabled = !busy,
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
            ClearAirPrimaryButton(text = if (busy) "Checking RPC…" else "Add Network", onClick = onAdd, enabled = !busy)
            refusal?.takeIf { it.field == null }?.let {
                Spacer(Modifier.height(ClearAirSpacing.xs))
                ClearAirStatusMark(glyph = "!", text = it.message.orEmpty(), tone = ClearAirTone.Failed)
            }
        }
    }
}

/** The words for [field] of the form, when the refusal concerns it. */
private fun errorOf(refusal: EvmRefusal?, field: CustomNetworkField): String? = refusal?.takeIf { it.field == field }?.message
