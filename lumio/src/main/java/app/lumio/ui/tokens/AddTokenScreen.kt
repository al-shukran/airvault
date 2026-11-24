package app.lumio.ui.tokens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.tokens.Token
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirListRow
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.settings.SettingsFrame
import app.lumio.ui.settings.SettingsGroup

/**
 * Add Token (J10): a token of one chain, found by its contract address. The address is looked up when the owner leaves the field or presses
 * Done, and the token it names shows with its symbol, name and decimals above the Add Token button. A note sits above the button, because a token's
 * name proves nothing. Below, the chain's bundled tokens and the owner's own, and Remove on the owner's. The screen keeps no state of its own:
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
    offline: String?,
    onAddress: (String) -> Unit,
    onLookUp: () -> Unit,
    onAdd: (Token) -> Unit,
    onCancel: () -> Unit,
    onRemove: (Token) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SettingsFrame(title = "Add Token", offline = offline, onBack = onBack, onLock = onLock, backLabel = "Networks") {
        ClearAirRow(title = chainName, description = "Network for this token", modifier = Modifier.padding(top = ClearAirSpacing.md))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
            ClearAirField(
                label = "Token Contract Address",
                value = address,
                onValueChange = onAddress,
                placeholder = "0x…",
                monospace = true,
                error = problem,
                onCommit = { if (address.isNotBlank() && !busy) onLookUp() },
                inputTag = "token-address",
                trailing = {
                    ClearAirTextAction(
                        text = "Paste",
                        onClick = { clipboard.getText()?.text?.let(onAddress) },
                        enabled = !busy,
                        modifier = Modifier.testTag("token-paste"),
                    )
                },
            )
        }
        found?.let { token ->
            SettingsGroup(title = "Found on $chainName") {
                ClearAirRow(title = "Symbol", description = token.symbol, modifier = Modifier.testTag("token-found"))
                ClearAirRow(title = "Name", description = token.name)
                ClearAirRow(title = "Decimals", description = token.decimals.toString())
            }
            ClearAirNote(text = "Anyone can create a token with any name. Add only tokens you trust.", modifier = Modifier.padding(top = ClearAirSpacing.md))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
                ClearAirPrimaryButton(text = "Add Token", onClick = { onAdd(token) }, enabled = !busy, modifier = Modifier.testTag("token-add"))
                ClearAirTextAction(text = "Cancel", onClick = onCancel, enabled = !busy)
            }
        }
        SettingsGroup(title = "Default Tokens") {
            if (defaults.isEmpty()) ClearAirNote(text = "No default tokens on this chain.")
            defaults.forEach { token -> ClearAirListRow(title = token.symbol, description = tokenWords(token)) }
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        SettingsGroup(title = "Your Tokens") {
            if (custom.isEmpty()) ClearAirNote(text = "No tokens added yet.")
            custom.forEach { token ->
                ClearAirListRow(
                    title = token.symbol,
                    description = tokenWords(token),
                    trailing = { ClearAirTextAction(text = "Remove", onClick = { onRemove(token) }, enabled = !busy) },
                )
            }
        }
    }
}

/** A token's name and decimals, as its list row says them. */
private fun tokenWords(token: Token): String = "${token.name} · ${token.decimals} decimals"
