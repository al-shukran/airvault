package app.lumio.ui.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsSection
import app.lensly.ui.components.TextAction
import app.lensly.ui.theme.LenslySpacing
import app.lumio.tokens.Token

/**
 * The tokens of one chain: a field for a contract address, a lookup that reads the token from the chain, the token it found with an Add button, and the
 * list of the chain's tokens, the bundled defaults and the owner's own. The owner's tokens have a Remove action. The screen keeps no state of its own:
 * [address], [found] and [problem] come from the caller, and each action goes back to it. [busy] holds the controls while a read is running.
 */
@Composable
fun AddTokenScreen(
    chainName: String,
    defaults: List<Token>,
    custom: List<Token>,
    address: String,
    busy: Boolean,
    found: Token?,
    problem: String?,
    onAddress: (String) -> Unit,
    onLookUp: () -> Unit,
    onAdd: (Token) -> Unit,
    onCancel: () -> Unit,
    onRemove: (Token) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Tokens on $chainName", modifier = modifier, onBack = onBack) {
        Text(
            "Paste the contract address of an ERC-20 token. Lumio reads its symbol, name and decimals from this chain. Nothing is saved until you add it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        AddressField(address = address, busy = busy, onAddress = onAddress, onLookUp = onLookUp)
        problem?.let {
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
        found?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            FoundToken(token = it, chainName = chainName, busy = busy, onAdd = onAdd, onCancel = onCancel)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        SettingsSection(title = "Default Tokens") {
            if (defaults.isEmpty()) EmptyLine("No default tokens on this chain.")
            defaults.forEach { TokenLine(token = it, onRemove = null) }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        SettingsSection(title = "Your Tokens") {
            if (custom.isEmpty()) EmptyLine("No tokens added yet.")
            custom.forEach { token -> TokenLine(token = token, onRemove = { onRemove(token) }) }
        }
    }
}

/** The contract field, with Paste beside it and the Look up button under it. */
@Composable
private fun AddressField(address: String, busy: Boolean, onAddress: (String) -> Unit, onLookUp: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = address,
        onValueChange = onAddress,
        modifier = Modifier.fillMaxWidth().testTag("token-address"),
        label = { Text("Contract Address") },
        placeholder = { Text("0x…") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextAction(
            text = "Paste",
            onClick = { clipboard.getText()?.text?.let(onAddress) },
            enabled = !busy,
            modifier = Modifier.testTag("token-paste"),
        )
        Button(
            onClick = onLookUp,
            enabled = !busy && address.isNotBlank(),
            modifier = Modifier.testTag("token-lookup"),
        ) {
            Text("Look Up")
        }
    }
}

/** The token a lookup found, with its symbol, name and decimals, and the buttons that add it or drop it. */
@Composable
private fun FoundToken(token: Token, chainName: String, busy: Boolean, onAdd: (Token) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag("token-found")) {
        Text(
            "Found on $chainName",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            token.symbol,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${token.name} · ${token.decimals} decimals",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            token.contract,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onAdd(token) }, enabled = !busy, modifier = Modifier.testTag("token-add")) { Text("Add Token") }
            TextAction(text = "Cancel", onClick = onCancel, enabled = !busy)
        }
    }
}

/** One token of a list: its symbol and name on the left, and its Remove action on the right when the owner may remove it. */
@Composable
private fun TokenLine(token: Token, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(token.symbol, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${token.name} · ${token.decimals} decimals",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) TextAction(text = "Remove", onClick = onRemove, destructive = true)
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = LenslySpacing.xs))
}
