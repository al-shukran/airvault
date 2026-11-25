package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a field, and how far its label reaches above the top edge. */
private val FIELD_HEIGHT = 56.dp
private val LABEL_OVERHANG = 9.dp

/** The corner radius of a field, the proposal's 16 dp. */
private val FIELD_CORNER = 16.dp

/** How far the notch for the label starts from the field's left edge, and the space either side of the label inside the notch. */
private val NOTCH_START = 12.dp
private val NOTCH_PAD = 5.dp

/**
 * A text field of a Clear Air form: an outline with its [label] set in a notch of the top edge, the value inside, and an optional [trailing] action
 * such as Paste, Scan or the eye of a password. Empty, it shows [placeholder] in the glyph ink. The outline is 2 dp in ink while the field has focus.
 * With an [error] the outline and label turn rose and the words sit under the field with a mark, so colour is never the only signal. [monospace] sets an address in the address style, and a long value wraps inside the field. [masked] shows
 * the value as dots, for a password, and makes the field secret: its keyboard gets the secret input flags. On a secret field Enter, typed or on a
 * hardware keyboard, moves focus to the next field, or runs [onSubmit] on the last one, and never adds a line break. [onCommit] runs when the
 * owner leaves the field or presses Done, so a value can be applied once it is complete rather than on every key. [inputTag] tags the text
 * input itself, for tests. [transformation] shows the value in another form, such as an address in groups of four, and leaves the value as it is.
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
    transformation: VisualTransformation = VisualTransformation.None,
    onSubmit: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onCommit: (() -> Unit)? = null,
    inputTag: String? = null,
) {
    val palette = LocalClearAirPalette.current
    val focus = LocalFocusManager.current
    val style = if (monospace) ClearAirType.address else ClearAirType.body
    var focused by remember { mutableStateOf(false) }
    // Done or Enter leaves a field with onCommit, which applies its value. Otherwise a secret field's last Enter submits, and any other Enter
    // hands focus to the next field.
    val enter: () -> Unit = {
        when {
            onCommit != null -> focus.clearFocus()
            onSubmit != null -> onSubmit()
            else -> focus.moveFocus(FocusDirection.Next)
        }
    }
    val edge = when {
        error != null -> palette.rose
        focused -> palette.ink
        else -> palette.ink3
    }
    val edgeWidth = if (focused || error != null) 2.dp else 1.5.dp
    var labelWidth by remember { mutableFloatStateOf(0f) }
    Column(modifier = modifier.fillMaxWidth()) {
        // The label sits half above the field's top edge, so the box makes room for it.
        Box(modifier = Modifier.padding(top = LABEL_OVERHANG)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(rejectOf(failed = error != null))
                    .heightIn(min = FIELD_HEIGHT)
                    .drawBehind {
                        val stroke = edgeWidth.toPx()
                        val notchStart = NOTCH_START.toPx()
                        val notchEnd = notchStart + labelWidth + 2 * NOTCH_PAD.toPx()
                        clipRect(left = notchStart, top = -stroke * 4, right = notchEnd, bottom = stroke * 4, clipOp = ClipOp.Difference) {
                            drawRoundRect(
                                color = edge,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius(FIELD_CORNER.toPx() - stroke / 2),
                                style = Stroke(width = stroke),
                            )
                        }
                    }
                    .padding(start = ClearAirSpacing.md, end = ClearAirSpacing.xxs, top = ClearAirSpacing.xxs, bottom = ClearAirSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A masked field, or one on a password keyboard, holds a secret: it gets the secret input flags and no autocorrect.
                val secret = masked || keyboard == KeyboardType.Password
                val input: @Composable () -> Unit = {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = ClearAirSpacing.xs)
                            .then(inputTag?.let { Modifier.testTag(it) } ?: Modifier)
                            .onFocusChanged { state ->
                                if (focused && !state.isFocused) onCommit?.invoke()
                                focused = state.isFocused
                            }
                            .onPreviewKeyEvent { event ->
                                val enterKey = secret && event.key == Key.Enter
                                if (enterKey && event.type == KeyEventType.KeyDown) enter()
                                enterKey
                            },
                        textStyle = style.copy(color = palette.ink),
                        singleLine = secret,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboard,
                            autoCorrectEnabled = !secret,
                            imeAction = when {
                                onCommit != null -> ImeAction.Done
                                !secret -> ImeAction.Default
                                onSubmit != null -> ImeAction.Done
                                else -> ImeAction.Next
                            },
                        ),
                        keyboardActions = KeyboardActions(onNext = { enter() }, onDone = { enter() }),
                        visualTransformation = if (masked) PasswordVisualTransformation() else transformation,
                        cursorBrush = SolidColor(palette.ink),
                        decorationBox = { inner ->
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(text = placeholder, style = style, color = palette.ink3)
                            }
                            inner()
                        },
                    )
                }
                if (secret) SecretInput(input) else input()
                trailing?.invoke()
            }
            Text(
                text = label,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = NOTCH_START + NOTCH_PAD, y = -LABEL_OVERHANG)
                    .onSizeChanged { labelWidth = it.width.toFloat() },
                style = ClearAirType.label.copy(fontSize = 11.5.sp, lineHeight = 18.sp),
                color = if (error != null) palette.rose else palette.ink2,
            )
            }
        error?.let {
            Row(modifier = Modifier.padding(start = 2.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
                ClearAirIcon(glyph = ClearAirGlyph.ALERT, color = palette.rose, size = 15.dp, modifier = Modifier.padding(top = 1.5.dp))
                Spacer(Modifier.width(6.dp))
                Text(text = it, style = ClearAirType.label.copy(fontWeight = FontWeight.Normal), color = palette.rose)
            }
        }
    }
}

/** The eye at the end of a password [ClearAirField]: open while the password is hidden, and struck through once it shows. */
@Composable
fun ClearAirEyeToggle(shown: Boolean, onToggle: () -> Unit) {
    ClearAirIconButton(
        glyph = if (shown) ClearAirGlyph.EYE_OFF else ClearAirGlyph.EYE,
        label = if (shown) "Hide password" else "Show password",
        onClick = onToggle,
    )
}
