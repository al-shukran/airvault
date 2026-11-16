package app.lensly.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** Said when Paste finds no text on the clipboard. */
internal const val CLIPBOARD_EMPTY = "The clipboard has no text."

/**
 * A field for an address the owner types or pastes. The Paste action at its end replaces the field with the clipboard's text, trimmed.
 * [validate] returns the words that say why a value is not an address of the network, or null when it is one. The words show under the
 * field once it has text, and the field is marked as an error while they show. [onScan], when given, adds a scan action beside Paste
 * that opens the camera; the caller decides what the scan fills in.
 */
@Composable
fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    validate: (String) -> String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onScan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val error = if (value.isEmpty()) null else validate(value)
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(error) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = supporting,
        trailingIcon = {
            Row {
                TextButton(
                    onClick = { pasteAddress(context, onValueChange) },
                    enabled = enabled,
                ) {
                    Text("Paste")
                }
                if (onScan != null) {
                    IconButton(onClick = onScan, enabled = enabled) {
                        Icon(LenslyIcons.QrScan, contentDescription = "Scan a QR code")
                    }
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** The trimmed text on the clipboard, or null when there is none. */
internal fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Puts the clipboard's text into the field. An empty clipboard says so, and the field keeps what it had. */
private fun pasteAddress(context: Context, onText: (String) -> Unit) {
    val text = clipboardText(context)
    if (text == null) {
        Toast.makeText(context, CLIPBOARD_EMPTY, Toast.LENGTH_SHORT).show()
    } else {
        onText(text)
    }
}
