package app.lumio.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.core.btc.BtcNetwork
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.theme.LenslySpacing
import app.lumio.btc.EsploraServers
import kotlinx.coroutines.delay

/** How long a server text must stay as it is before a valid server is applied, so a half-typed address is not applied while the owner types. */
private const val SERVER_PAUSE_MS = 1_000L

/**
 * The Bitcoin section: the Esplora server that each Bitcoin network reads from, as the base URLs [mainnet] and [testnet4] that are in use. Each field
 * checks its text as the owner types. A valid server is applied when the owner pauses, leaves the field, or presses the keyboard's action. An address
 * that is not https is refused under its field, and nothing is applied. [error] shows a change that the vault refused. [onApply] takes the network and
 * its new server, or null for the public one. Reset puts both fields back on the public servers, and [onReset] saves that.
 */
@Composable
fun BitcoinServersSettings(
    mainnet: String,
    testnet4: String,
    error: String?,
    onApply: (BtcNetwork, String?) -> Unit,
    onReset: () -> Unit,
) {
    var resets by remember { mutableIntStateOf(0) }
    Text(
        "Lumio asks the server for each address it watches, so the server learns those addresses. Use an Esplora server you trust. " +
            "Each network has its own server, and an address must start with https://.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LenslySpacing.md))
    ServerField(
        label = "Mainnet",
        network = BtcNetwork.MAINNET,
        current = mainnet,
        resets = resets,
        imeAction = ImeAction.Next,
        onApply = onApply,
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    ServerField(
        label = "Testnet4",
        network = BtcNetwork.TESTNET4,
        current = testnet4,
        resets = resets,
        imeAction = ImeAction.Done,
        onApply = onApply,
    )
    error?.let {
        Spacer(Modifier.height(LenslySpacing.md))
        InlineBanner(message = it, tone = BannerTone.Error)
    }
    SettingsRow(
        title = "Reset to Defaults",
        onClick = {
            resets++
            onReset()
        },
    )
}

/**
 * One server field. [current] is the base URL the network reads from now. [resets] counts the Reset presses: each one puts the text back on the public
 * server, without a save of its own. An entry is applied only when it is valid and names a server other than the last one asked for.
 */
@Composable
private fun ServerField(
    label: String,
    network: BtcNetwork,
    current: String,
    resets: Int,
    imeAction: ImeAction,
    onApply: (BtcNetwork, String?) -> Unit,
) {
    val defaultUrl = EsploraServers.defaultFor(network)
    var text by remember { mutableStateOf(current) }
    // The server last asked for. It starts as the one in use, and is compared with the text.
    var requested by remember { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    val edit = serverEdit(network, text, requested)

    fun apply() {
        val next = serverEdit(network, text, requested)
        if (next is ServerEdit.Apply) {
            requested = next.url ?: defaultUrl
            onApply(network, next.url)
        }
    }

    LaunchedEffect(resets) {
        if (resets > 0) {
            text = defaultUrl
            requested = defaultUrl
        }
    }
    LaunchedEffect(text) {
        if (serverEdit(network, text, requested) is ServerEdit.Apply) {
            delay(SERVER_PAUSE_MS)
            apply()
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        isError = edit is ServerEdit.Refused,
        supportingText = { Text(supportText(edit, current, defaultUrl)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { apply() }, onDone = { apply() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(serverTag(network))
            .onFocusChanged { state ->
                if (focused && !state.isFocused) apply()
                focused = state.isFocused
            },
    )
}

/** The test tag of the field of [network]. */
fun serverTag(network: BtcNetwork): String = "server-${network.name.lowercase()}"

/** The line under a server field: the refusal, the wait for the owner to finish, or which server is in use. */
private fun supportText(edit: ServerEdit, current: String, defaultUrl: String): String = when (edit) {
    is ServerEdit.Refused -> edit.reason
    is ServerEdit.Apply -> "Applies when you finish typing."
    ServerEdit.Unchanged -> if (current == defaultUrl) "The public server." else "Your own server."
}
