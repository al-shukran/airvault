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

/** The test tag of the Ordinals indexer's field. */
const val INDEXER_TAG = "server-indexer"

/**
 * The Bitcoin section: the Esplora server that each Bitcoin network reads from, as the base URLs [mainnet] and [testnet4] that are in use, and the
 * Ordinals indexer that [indexer] names ("" while it is off). Each field checks its text as the owner types. A valid address is applied when the owner
 * pauses, leaves the field, or presses the keyboard's action. An address that is not https is refused under its field, and nothing is applied. [error]
 * shows a change that the vault refused. [onApply] takes the network and its new server, or null for the public one, and [onIndexer] takes the
 * indexer's address, or null to turn it off. Reset puts every field back on its default, and [onReset] saves that.
 */
@Composable
fun BitcoinServersSettings(
    mainnet: String,
    testnet4: String,
    indexer: String,
    error: String?,
    onApply: (BtcNetwork, String?) -> Unit,
    onIndexer: (String?) -> Unit,
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
        tag = serverTag(BtcNetwork.MAINNET),
        current = mainnet,
        rules = esploraRules(BtcNetwork.MAINNET),
        resets = resets,
        imeAction = ImeAction.Next,
        onApply = { onApply(BtcNetwork.MAINNET, it) },
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    ServerField(
        label = "Testnet4",
        tag = serverTag(BtcNetwork.TESTNET4),
        current = testnet4,
        rules = esploraRules(BtcNetwork.TESTNET4),
        resets = resets,
        imeAction = ImeAction.Next,
        onApply = { onApply(BtcNetwork.TESTNET4, it) },
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    ServerField(
        label = "Ordinals Indexer",
        tag = INDEXER_TAG,
        current = indexer,
        rules = INDEXER_RULES,
        resets = resets,
        imeAction = ImeAction.Done,
        onApply = onIndexer,
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
 * What one address field accepts and says. [default] is the address that Reset puts back, and "" for an indexer that is off. [edit] checks the text
 * against the address last asked for. The words under the field are [defaultWords] while the address in use is the default, and [ownWords] otherwise.
 */
private class AddressRules(
    val default: String,
    val edit: (text: String, requested: String) -> ServerEdit,
    val defaultWords: String,
    val ownWords: String,
)

/** The rules of an Esplora field: its public server is the default, and its text is checked as [serverEdit] checks it. */
private fun esploraRules(network: BtcNetwork) = AddressRules(
    default = EsploraServers.defaultFor(network),
    edit = { text, requested -> serverEdit(network, text, requested) },
    defaultWords = "The public server.",
    ownWords = "Your own server.",
)

/** The rules of the Ordinals indexer field: off is its default, and its text is checked as [indexerEdit] checks it. */
private val INDEXER_RULES = AddressRules(
    default = "",
    edit = ::indexerEdit,
    defaultWords = "Off. Lumio checks no output for inscriptions or runes.",
    ownWords = "Your ord server. Lumio asks it about each output you spend.",
)

/**
 * One address field. [current] is the address in use. [resets] counts the Reset presses: each one puts the text back on the default, without a save of its
 * own. An entry is applied only when it is valid and names an address other than the last one asked for.
 */
@Composable
private fun ServerField(
    label: String,
    tag: String,
    current: String,
    rules: AddressRules,
    resets: Int,
    imeAction: ImeAction,
    onApply: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    // The address last asked for. It starts as the one in use, and is compared with the text.
    var requested by remember { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    val edit = rules.edit(text, requested)

    fun apply() {
        val next = rules.edit(text, requested)
        if (next is ServerEdit.Apply) {
            requested = next.url ?: rules.default
            onApply(next.url)
        }
    }

    LaunchedEffect(resets) {
        if (resets > 0) {
            text = rules.default
            requested = rules.default
        }
    }
    LaunchedEffect(text) {
        if (rules.edit(text, requested) is ServerEdit.Apply) {
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
        supportingText = { Text(supportText(edit, current, rules)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { apply() }, onDone = { apply() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) apply()
                focused = state.isFocused
            },
    )
}

/** The test tag of the field of [network]. */
fun serverTag(network: BtcNetwork): String = "server-${network.name.lowercase()}"

/** The line under a field: the refusal, the wait for the owner to finish, or which address is in use. */
private fun supportText(edit: ServerEdit, current: String, rules: AddressRules): String = when (edit) {
    is ServerEdit.Refused -> edit.reason
    is ServerEdit.Apply -> "Applies when you finish typing."
    ServerEdit.Unchanged -> if (current == rules.default) rules.defaultWords else rules.ownWords
}
