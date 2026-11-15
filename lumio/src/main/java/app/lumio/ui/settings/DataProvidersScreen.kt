package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.theme.LenslySpacing
import app.lumio.R
import kotlin.math.roundToInt

/**
 * One provider as the settings list it. [apiKey] is the key in the vault, or null. [status] is what the scheduler and the settings say now. The move
 * flags say whether the provider can go up or down within its group.
 */
data class ProviderRow(
    val id: String,
    val mevProtected: Boolean,
    val acceptsKey: Boolean,
    val enabled: Boolean,
    val apiKey: String?,
    val status: ProviderStatus,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * Lumio's data providers: the chain-data providers, then the RPC providers. Each row has its enable switch, its move buttons, its status, a MEV badge
 * when it has one, and a key field when it takes a key. The screen keeps no state of its own: each change goes to the caller, as [onEnabled],
 * [onMove] or [onApiKey]. An empty key in [onApiKey] clears the saved one.
 */
@Composable
fun DataProvidersScreen(
    data: List<ProviderRow>,
    rpc: List<ProviderRow>,
    onBack: () -> Unit,
    onEnabled: (id: String, on: Boolean) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    onApiKey: (id: String, key: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = stringResource(R.string.providers_title), modifier = modifier, onBack = onBack) {
        Text(
            stringResource(R.string.providers_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderGroup(stringResource(R.string.providers_data_heading), data, onEnabled, onMove, onApiKey)
        ProviderGroup(stringResource(R.string.providers_rpc_heading), rpc, onEnabled, onMove, onApiKey)
    }
}

@Composable
private fun ProviderGroup(
    heading: String,
    rows: List<ProviderRow>,
    onEnabled: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onApiKey: (String, String) -> Unit,
) {
    Spacer(Modifier.height(LenslySpacing.lg))
    Text(
        heading,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    rows.forEach { row ->
        Spacer(Modifier.height(LenslySpacing.md))
        ProviderRowItem(row, onEnabled, onMove, onApiKey)
    }
}

@Composable
private fun ProviderRowItem(
    row: ProviderRow,
    onEnabled: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onApiKey: (String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.id,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.mevProtected) {
                PillChip(text = stringResource(R.string.providers_mev_badge))
                Spacer(Modifier.width(LenslySpacing.sm))
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = { onEnabled(row.id, it) },
                modifier = Modifier.testTag("enable-${row.id}"),
            )
        }
        Text(
            statusText(row.status),
            style = MaterialTheme.typography.bodySmall,
            color = if (row.status is ProviderStatus.CircuitOpen) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = stringResource(R.string.providers_move_up),
                onClick = { onMove(row.id, true) },
                enabled = row.canMoveUp,
                modifier = Modifier.testTag("move-up-${row.id}"),
            )
            Spacer(Modifier.width(LenslySpacing.sm))
            PillButton(
                text = stringResource(R.string.providers_move_down),
                onClick = { onMove(row.id, false) },
                enabled = row.canMoveDown,
                modifier = Modifier.testTag("move-down-${row.id}"),
            )
        }
        if (row.acceptsKey) {
            Spacer(Modifier.height(LenslySpacing.sm))
            ApiKeyField(row.id, row.apiKey) { onApiKey(row.id, it) }
        }
    }
}

/**
 * The key field of one provider. The key shows as dots until the owner taps Show. Save stores the typed key, and Clear removes the saved one. The
 * field holds the key only while it is on screen, and nothing here logs it.
 */
@Composable
private fun ApiKeyField(id: String, stored: String?, onSave: (String) -> Unit) {
    var draft by remember(id, stored) { mutableStateOf(stored.orEmpty()) }
    var revealed by remember(id) { mutableStateOf(false) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.providers_key_label)) },
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("key-$id"),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { revealed = !revealed }, modifier = Modifier.testTag("reveal-$id")) {
            Text(stringResource(if (revealed) R.string.providers_key_hide else R.string.providers_key_show))
        }
        PillButton(
            text = stringResource(R.string.providers_key_save),
            onClick = { onSave(draft.trim()) },
            enabled = draft.isNotBlank() && draft.trim() != stored.orEmpty(),
            modifier = Modifier.testTag("save-key-$id"),
        )
        Spacer(Modifier.width(LenslySpacing.sm))
        PillButton(
            text = stringResource(R.string.providers_key_clear),
            onClick = { onSave("") },
            enabled = !stored.isNullOrEmpty(),
            modifier = Modifier.testTag("clear-key-$id"),
        )
    }
}

@Composable
private fun statusText(status: ProviderStatus): String = when (status) {
    ProviderStatus.Off -> stringResource(R.string.status_off)
    ProviderStatus.NeedsKey -> stringResource(R.string.status_needs_key)
    ProviderStatus.NotActive -> stringResource(R.string.status_not_active)
    is ProviderStatus.CircuitOpen -> stringResource(R.string.status_circuit_open, wholeSeconds(status.retryInMs))
    is ProviderStatus.CoolingDown -> stringResource(R.string.status_cooling_down, wholeSeconds(status.inMs))
    is ProviderStatus.Healthy -> stringResource(R.string.status_healthy, status.score.roundToInt())
    ProviderStatus.Ready -> stringResource(R.string.status_ready)
}

/** A time in milliseconds, rounded up to whole seconds. */
private fun wholeSeconds(ms: Long): Int = ((ms + 999L) / 1000L).toInt()
