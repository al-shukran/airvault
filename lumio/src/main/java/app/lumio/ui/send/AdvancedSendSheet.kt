package app.lumio.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lumio.evm.EvmAdvanced
import app.lumio.evm.EvmAdvancedCheck
import app.lumio.evm.EvmField
import app.lumio.evm.PreparedSend

/**
 * The advanced sheet of an Ethereum send: the nonce, the gas limit and the EIP-1559 fees, slid up over the review. [current] is the send the
 * sheet starts from and [suggested] is the one the network prepared. Save hands back the edited send; Cancel discards the edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSendSheet(suggested: PreparedSend, current: PreparedSend, onSave: (PreparedSend) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AdvancedSendFields(suggested = suggested, current = current, onSave = onSave, onCancel = onDismiss)
    }
}

/** The fields of the advanced sheet without the sheet, so a screenshot can show them. Save is off until every field is valid. */
@Composable
fun AdvancedSendFields(suggested: PreparedSend, current: PreparedSend, onSave: (PreparedSend) -> Unit, onCancel: () -> Unit) {
    var fields by remember { mutableStateOf(EvmAdvanced.fieldsOf(current)) }
    val check = EvmAdvanced.check(suggested, fields)
    val valid = check as? EvmAdvancedCheck.Valid
    val errors = (check as? EvmAdvancedCheck.Invalid)?.errors.orEmpty()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm)) {
        Text("Advanced", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Prefilled from the network. Lensly signs the values you save here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        NumberField(
            label = "Nonce",
            value = fields.nonce,
            error = errors[EvmField.NONCE],
            keyboard = KeyboardType.Number,
            onChange = { fields = fields.copy(nonce = it) },
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        NumberField(
            label = "Gas limit",
            value = fields.gasLimit,
            error = errors[EvmField.GAS_LIMIT],
            keyboard = KeyboardType.Number,
            onChange = { fields = fields.copy(gasLimit = it) },
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        NumberField(
            label = "Max fee per gas (gwei)",
            value = fields.maxFeeGwei,
            error = errors[EvmField.MAX_FEE],
            keyboard = KeyboardType.Decimal,
            onChange = { fields = fields.copy(maxFeeGwei = it) },
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        NumberField(
            label = "Max priority fee per gas (gwei)",
            value = fields.maxPriorityGwei,
            error = errors[EvmField.MAX_PRIORITY],
            keyboard = KeyboardType.Decimal,
            onChange = { fields = fields.copy(maxPriorityGwei = it) },
        )
        valid?.warnings?.forEach { warning ->
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = warning, tone = BannerTone.Warning)
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        SheetButtons(
            onReset = { fields = EvmAdvanced.fieldsOf(suggested) },
            onCancel = onCancel,
            onSave = { valid?.let { onSave(it.send) } },
            saveEnabled = valid != null,
        )
    }
}

/** A numeric field. [error] is the words under it, and the field is marked as an error while they show. */
@Composable
private fun NumberField(label: String, value: String, error: String?, keyboard: KeyboardType, onChange: (String) -> Unit) {
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(error) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = supporting,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Reset on its own line; Cancel and Save share the next. Save is a primary pill and Cancel an outline one. */
@Composable
internal fun SheetButtons(onReset: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean) {
    TextButton(onClick = onReset) {
        Text("Reset to suggested")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletPill(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f), style = WalletPillStyle.Outline)
        WalletPill(
            text = "Save",
            onClick = onSave,
            modifier = Modifier.weight(1f),
            style = WalletPillStyle.Primary,
            enabled = saveEnabled,
        )
    }
}
