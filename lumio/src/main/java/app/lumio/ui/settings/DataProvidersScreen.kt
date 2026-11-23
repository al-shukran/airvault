package app.lumio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SettingsSection
import app.lensly.ui.components.SettingsSwitchRow
import app.lensly.ui.components.TextAction
import app.lensly.ui.theme.LenslySpacing
import app.lumio.R
import app.lumio.ui.kit.SecretInput
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
 * Lumio's data providers: the chain-data providers, then the RPC providers. Each provider is a switch row: the switch turns it on or off, and
 * its footer holds its status, a MEV badge when it has one, the order buttons, and a key field when it takes a key. The screen keeps no state of
 * its own: each change goes to the caller, as [onEnabled], [onMove] or [onApiKey]. An empty key in [onApiKey] clears the saved one.
 */
@Composable
fun DataProvidersScreen(
    data: List<ProviderRow>,
    rpc: List<ProviderRow>,
    onBack: () -> Unit,
    onEnabled: (id: String, on: Boolean) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    onApiKey: (id: String, key: String) -> Unit,
    preferRpc: Boolean = false,
    onPreferRpc: (Boolean) -> Unit = {},
    prices: List<ProviderRow> = emptyList(),
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = stringResource(R.string.providers_title), modifier = modifier, onBack = onBack) {
        Text(
            stringResource(R.string.providers_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrivacyNote(preferRpc, onPreferRpc)
        ProviderGroup(stringResource(R.string.providers_data_heading), data, onEnabled, onMove, onApiKey)
        if (prices.isNotEmpty()) {
            ProviderGroup(stringResource(R.string.providers_prices_heading), prices, onEnabled, onMove, onApiKey)
        }
        ProviderGroup(stringResource(R.string.providers_rpc_heading), rpc, onEnabled, onMove, onApiKey)
    }
}

/** Which providers see the addresses a balance read asks for, and the switch that keeps the explorers out of those reads. */
@Composable
private fun PrivacyNote(preferRpc: Boolean, onPreferRpc: (Boolean) -> Unit) {
    Spacer(Modifier.height(LenslySpacing.lg))
    Text(
        stringResource(R.string.providers_privacy_heading),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    Text(
        stringResource(R.string.providers_privacy_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LenslySpacing.md))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.providers_prefer_rpc_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.providers_prefer_rpc_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = preferRpc,
            onCheckedChange = onPreferRpc,
            modifier = Modifier.testTag("prefer-rpc"),
        )
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
    SettingsSection(title = heading) {
        rows.forEach { row -> ProviderRowItem(row, onEnabled, onMove, onApiKey) }
    }
}

@Composable
private fun ProviderRowItem(
    row: ProviderRow,
    onEnabled: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onApiKey: (String, String) -> Unit,
) {
    SettingsSwitchRow(
        title = row.id,
        checked = row.enabled,
        onCheckedChange = { onEnabled(row.id, it) },
        modifier = Modifier.testTag("enable-${row.id}"),
        footer = {
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
                if (row.mevProtected) {
                    PillChip(text = stringResource(R.string.providers_mev_badge))
                    Spacer(Modifier.width(LenslySpacing.sm))
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onMove(row.id, true) },
                    enabled = row.canMoveUp,
                    modifier = Modifier.testTag("move-up-${row.id}"),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.providers_move_up))
                }
                IconButton(
                    onClick = { onMove(row.id, false) },
                    enabled = row.canMoveDown,
                    modifier = Modifier.testTag("move-down-${row.id}"),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.providers_move_down))
                }
            }
            if (row.acceptsKey) {
                Spacer(Modifier.height(LenslySpacing.xs))
                ApiKeyField(row.id, row.apiKey) { onApiKey(row.id, it) }
            }
            Spacer(Modifier.height(LenslySpacing.xs))
        },
    )
}

/**
 * The key field of one provider. The key shows as dots until the owner turns on Show key, a switch row. The typed key is saved when the owner leaves
 * the field or presses Done, never while the owner is typing, and Clear removes the saved one. The field holds the key only while it is on screen, and
 * nothing here logs it.
 */
@Composable
internal fun ApiKeyField(id: String, stored: String?, onSave: (String) -> Unit) {
    var draft by remember(id, stored) { mutableStateOf(stored.orEmpty()) }
    var revealed by remember(id) { mutableStateOf(false) }
    var focused by remember(id) { mutableStateOf(false) }
    // A key is saved only when it is not blank and differs from the saved one.
    fun save() {
        val key = draft.trim()
        if (key.isNotEmpty() && key != stored.orEmpty()) onSave(key)
    }
    SecretInput {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.providers_key_label)) },
            singleLine = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("key-$id")
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) save()
                    focused = state.isFocused
                },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.xxs)
            .toggleable(value = revealed, role = Role.Switch, onValueChange = { revealed = it })
            .testTag("reveal-$id"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.providers_key_reveal),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = revealed, onCheckedChange = null)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        TextAction(
            text = stringResource(R.string.providers_key_clear),
            onClick = { onSave("") },
            enabled = !stored.isNullOrEmpty(),
            modifier = Modifier.testTag("clear-key-$id"),
            destructive = true,
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
