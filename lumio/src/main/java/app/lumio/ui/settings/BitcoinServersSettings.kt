package app.lumio.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.core.btc.BtcNetwork
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.btc.EsploraServers
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirTone
import kotlinx.coroutines.delay

/** How long a server text must stay as it is before a valid server is applied, so a half-typed address is not applied while the owner types. */
private const val SERVER_PAUSE_MS = 1_000L

/** The test tag of the Ordinals indexer's field. */
const val INDEXER_TAG = "server-indexer"

/**
 * The Bitcoin section: the Esplora server that each Bitcoin network reads from, as the base URLs [mainnet] and [testnet4] that are in use, and the
 * Ordinals indexer that [indexer] names ("" while it is off). Each field checks its text as the owner types. A valid address is applied when the owner
 * pauses, leaves the field, or presses Done. An address that is not https is refused under its field, and nothing is applied. [error]
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
    ClearAirNote(
        text = "Lumio asks the server for each address it watches, so the server learns those addresses. Use an Esplora server you trust. " +
            "Each network has its own server, and an address must start with https://.",
    )
    ServerField(
        label = "Mainnet",
        tag = serverTag(BtcNetwork.MAINNET),
        current = mainnet,
        rules = esploraRules(BtcNetwork.MAINNET),
        resets = resets,
        onApply = { onApply(BtcNetwork.MAINNET, it) },
    )
    ServerField(
        label = "Testnet4",
        tag = serverTag(BtcNetwork.TESTNET4),
        current = testnet4,
        rules = esploraRules(BtcNetwork.TESTNET4),
        resets = resets,
        onApply = { onApply(BtcNetwork.TESTNET4, it) },
    )
    ServerField(
        label = "Ordinals Indexer",
        tag = INDEXER_TAG,
        current = indexer,
        rules = INDEXER_RULES,
        resets = resets,
        onApply = onIndexer,
    )
    error?.let {
        ClearAirStatusMark(
            glyph = "!",
            text = it,
            tone = ClearAirTone.Failed,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        )
    }
    ClearAirRow(
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
    ownWords = "Your ord server. All confirmed outputs of this account are sent to it.",
)

/**
 * One address field. [current] is the address in use. [resets] counts the Reset presses: each one puts the text back on the default, without a save of its
 * own. An entry is applied only when it is valid and names an address other than the last one asked for. A refusal shows under the field; otherwise the
 * field says which address is in use, or that the entry applies when the owner finishes typing.
 */
@Composable
private fun ServerField(
    label: String,
    tag: String,
    current: String,
    rules: AddressRules,
    resets: Int,
    onApply: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    // The address last asked for. It starts as the one in use, and is compared with the text.
    var requested by remember { mutableStateOf(current) }
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

    ClearAirField(
        label = label,
        value = text,
        onValueChange = { text = it },
        keyboard = KeyboardType.Uri,
        monospace = true,
        error = (edit as? ServerEdit.Refused)?.reason,
        onCommit = { apply() },
        inputTag = tag,
        modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xxs),
    )
    if (edit !is ServerEdit.Refused) {
        ClearAirNote(text = supportText(edit, current, rules), modifier = Modifier.padding(bottom = ClearAirSpacing.xs))
    }
}

/** The test tag of the field of [network]. */
fun serverTag(network: BtcNetwork): String = "server-${network.name.lowercase()}"

/** The line under a field: the refusal, the wait for the owner to finish, or which address is in use. */
private fun supportText(edit: ServerEdit, current: String, rules: AddressRules): String = when (edit) {
    is ServerEdit.Refused -> edit.reason
    is ServerEdit.Apply -> "Applies when you finish typing."
    ServerEdit.Unchanged -> if (current == rules.default) rules.defaultWords else rules.ownWords
}
