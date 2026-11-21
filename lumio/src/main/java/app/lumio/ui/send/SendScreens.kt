package app.lumio.ui.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import app.lensly.app.AddressNotes
import app.lensly.app.headline
import app.lensly.ui.components.AddressText
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletEyebrow
import app.lumio.ui.scan.LumioScanScreen

/** What the recipient scan says before a code is read. It stays on screen while the camera looks for one. */
internal const val RECIPIENT_SCAN_HINT = "Point the camera at an address or a payment code."

/** The camera scan of a recipient: an address or a payment code. [status] says what to scan, or why the last code was refused. */
@Composable
fun RecipientScanScreen(status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(title = "Scan Address", progress = null, statusText = status, onText = onText, onCancel = onCancel)
}

/** The camera scan of Lensly's signature. [progress] shows for an animated code, and [status] says what the scan is doing. */
@Composable
fun SignatureScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    LumioScanScreen(
        title = "Scan Signature",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onCancel,
    )
}

/**
 * The amount: the figure is set large where the owner reads it, and it is typed on the system's decimal keyboard. There is no keypad and no preset
 * amount.
 */
@Composable
internal fun AmountField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val figure = MaterialTheme.typography.displaySmall
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.sm),
        enabled = enabled,
        singleLine = true,
        textStyle = figure.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { field ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) {
                    Text("0", style = figure, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

@Composable
internal fun SectionLabel(text: String) {
    Spacer(Modifier.height(LenslySpacing.md))
    WalletEyebrow(text)
    Spacer(Modifier.height(LenslySpacing.xs))
}

@Composable
internal fun ReviewField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A [ReviewField] whose value is an address: the same label and text, with the copy action. The name the address goes by is shown above
 * it, so the owner sees whom it is for: an account of this device as "Your account", else the Address Book name. An address that is both
 * also shows its Address Book name below, as "Address Name".
 */
@Composable
internal fun ReviewAddress(label: String, address: String, notes: AddressNotes? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notes?.headline()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        if (notes?.internal != null) notes.entry?.let { entry -> ReviewField("Address Name", entry.name) }
        AddressText(address = address, icon = false, style = MaterialTheme.typography.bodyMedium)
    }
}
