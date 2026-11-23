package app.lumio.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * A text field of a Clear Air form: its [label] above it, the value inside a hairline border, and an optional [trailing] action such as Paste or
 * Scan. Empty, it shows [placeholder] in secondary ink. With an [error] the border turns rose and the words sit under the field with a mark, so
 * colour is never the only signal. [monospace] sets an address in the address style, and a long value wraps inside the field. [masked] shows
 * the value as dots, for a password, and makes the field secret: its keyboard gets the secret input flags.
 */
@Composable
fun ClearAirField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false,
    masked: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalClearAirPalette.current
    val style = if (monospace) ClearAirType.address else ClearAirType.body
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = ClearAirType.label, color = palette.ink2)
        Spacer(Modifier.height(ClearAirSpacing.xxs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(rejectOf(error))
                .heightIn(min = ClearAirSpacing.touchTarget)
                .border(1.dp, if (error != null) palette.rose else palette.hairline, RoundedCornerShape(ClearAirSpacing.sm))
                .padding(horizontal = ClearAirSpacing.md, vertical = ClearAirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A masked field, or one on a password keyboard, holds a secret: it gets the secret input flags and no autocorrect.
            val secret = masked || keyboard == KeyboardType.Password
            val input: @Composable () -> Unit = {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    textStyle = style.copy(color = palette.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = !secret),
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    cursorBrush = SolidColor(palette.ink),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(text = placeholder, style = style, color = palette.ink2)
                        }
                        inner()
                    },
                )
            }
            if (secret) SecretInput(input) else input()
            trailing?.invoke()
        }
        error?.let {
            Spacer(Modifier.height(ClearAirSpacing.xxs))
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed)
        }
    }
}
