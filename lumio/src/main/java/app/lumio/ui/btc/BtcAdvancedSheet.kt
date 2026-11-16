package app.lumio.ui.btc

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.theme.LenslySpacing
import app.lumio.btc.BtcAdvanced
import app.lumio.btc.BtcSendInput
import app.lumio.ui.send.SheetButtons

/**
 * The advanced sheet of a Bitcoin send, slid up over the review: the fee rate. [rate] is the rate the send is built at and [suggestedRate]
 * the server's estimate. Save hands back the rate the owner keeps; Cancel discards the edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtcAdvancedSheet(suggestedRate: Double, rate: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BtcAdvancedFields(suggestedRate = suggestedRate, rate = rate, onSave = onSave, onCancel = onDismiss)
    }
}

/** The fee-rate field of the advanced sheet without the sheet, so a screenshot can show it. Save is off while the rate is refused. */
@Composable
fun BtcAdvancedFields(suggestedRate: Double, rate: Double, onSave: (Double) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(rateText(rate)) }
    val parsed = runCatching { BtcSendInput.feeRate(text) }
    val value = parsed.getOrNull()
    val error = parsed.exceptionOrNull()?.message
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(error) }
    } else {
        null
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm)) {
        Text("Advanced", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "The coins stay the same. The fee and the change follow the rate that you save.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Fee rate in sat/vB") },
            singleLine = true,
            isError = error != null,
            supportingText = supporting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            "Suggested ${rateText(suggestedRate)} sat/vB, the server's estimate for about three blocks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value?.let { BtcAdvanced.warnings(suggestedRate, it) }.orEmpty().forEach { warning ->
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = warning, tone = BannerTone.Warning)
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        SheetButtons(
            onReset = { text = rateText(suggestedRate) },
            onCancel = onCancel,
            onSave = { value?.let { onSave(it) } },
            saveEnabled = value != null,
        )
    }
}
