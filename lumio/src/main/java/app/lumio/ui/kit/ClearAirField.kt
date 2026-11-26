package app.lumio.ui.kit

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * The mock's field is a CSS border box: at least 56 dp tall with the edge counted, 6 dp of padding inside the edge (16 dp at the start), and its
 * children 2 dp apart. A field with a trailing 44 dp button is therefore 59 dp tall, and 60 dp with the 2 dp edge. [LABEL_OVERHANG] is the room the
 * label's line box takes above the field, which keeps the space above a field the same whatever the edge.
 */
private val FIELD_HEIGHT = 56.dp
private val FIELD_PADDING = 6.dp
private val FIELD_START_PADDING = 16.dp
private val FIELD_GAP = 2.dp
private val TRAILING_HEIGHT = 44.dp
private val LABEL_OVERHANG = 9.dp

/** The mock's label is a 21 dp line box whose top is 9 dp above the inside of the edge, and the mock's value and error text sit in line boxes too. */
private val LABEL_LINE = 21.sp
private val VALUE_LINE = 21.sp

/** The mock sets an address inside a field at 13.5 sp on a 20 sp line with no tracking, a little larger and looser than the address style of a row. */
private val ADDRESS_VALUE_LINE = 20.sp

/** A masked value is dots in the mono face, 0.14 em apart, as the mock sets a password. */
/** A line set in the middle of its line box, as the mock's CSS sets it. */
private val FieldLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private val MASK_TRACKING = 0.14.em

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
 * input itself, for tests. [trailingHeight] caps how tall the actions lay out; a field whose mock has a short action, such as a text action, passes the room
 * inside its 56 dp edge so the field stays that tall. [transformation] shows the value in another form, such as an address in groups of four, and leaves the value as it is.
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
    trailingHeight: Dp = TRAILING_HEIGHT,
) {
    val palette = LocalClearAirPalette.current
    val focus = LocalFocusManager.current
    val style = if (monospace) {
        ClearAirType.address.copy(fontSize = 13.5.sp, lineHeight = ADDRESS_VALUE_LINE, letterSpacing = 0.sp, lineHeightStyle = FieldLine)
    } else {
        ClearAirType.body.copy(lineHeight = VALUE_LINE, lineHeightStyle = FieldLine)
    }
    // The placeholder keeps the body face: only the dots of a masked value take the mono face and its tracking.
    val valueStyle = if (masked) {
        ClearAirType.address.copy(fontSize = 15.sp, lineHeight = VALUE_LINE, letterSpacing = MASK_TRACKING, lineHeightStyle = FieldLine)
    } else {
        style
    }
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
        // The label's line box reaches above the field's top edge, so the box makes room for it.
        Box(modifier = Modifier.padding(top = LABEL_OVERHANG)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(rejectOf(failed = error != null))
                    .heightIn(min = FIELD_HEIGHT)
                    .drawBehind {
                        val stroke = edgeWidth.toPx()
                        val notchStart = stroke + NOTCH_START.toPx()
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
                    .padding(
                        start = edgeWidth + FIELD_START_PADDING,
                        end = edgeWidth + FIELD_PADDING,
                        top = edgeWidth + FIELD_PADDING,
                        bottom = edgeWidth + FIELD_PADDING,
                    ),
                horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
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
                        textStyle = valueStyle.copy(color = palette.ink),
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
                trailing?.let { Trailing(trailingHeight, it) }
            }
            Text(
                text = label,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = edgeWidth + NOTCH_START + NOTCH_PAD, y = edgeWidth - LABEL_OVERHANG)
                    .onSizeChanged { labelWidth = it.width.toFloat() },
                style = ClearAirType.label.copy(fontSize = 11.5.sp, lineHeight = LABEL_LINE, lineHeightStyle = FieldLine),
                color = if (error != null) palette.rose else palette.ink2,
            )
            }
        error?.let {
            Row(modifier = Modifier.padding(start = 2.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
                ClearAirIcon(glyph = ClearAirGlyph.ALERT, color = palette.rose, size = 15.dp, modifier = Modifier.padding(top = 1.5.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = it,
                    style = ClearAirType.label.copy(fontWeight = FontWeight.Normal, lineHeightStyle = FieldLine),
                    color = palette.rose,
                )
            }
        }
    }
}

/**
 * The actions at the end of a field, 2 dp apart. Their layout is at most [height] tall, the mock's 44 dp box unless the field says otherwise, however tall a
 * button's own 48 dp target is, so the target reaches past the box and the field keeps the mock's height.
 */
@Composable
private fun Trailing(height: Dp, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val layoutHeight = minOf(placeable.height, height.roundToPx())
            layout(placeable.width, layoutHeight) { placeable.place(0, (layoutHeight - placeable.height) / 2) }
        },
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
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
