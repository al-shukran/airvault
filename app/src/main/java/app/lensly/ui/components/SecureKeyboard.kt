package app.lensly.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySurfaceHigh
import app.lensly.ui.theme.SpaceMonoFamily

private enum class KeyboardPage { Letters, Symbols }

private val KEY_GAP = 4.dp
private val ROW_GAP = 8.dp
private val ROW_PADDING = 3.dp
private val KEY_HEIGHT = 54.dp
private val KEY_CORNER = 10.dp
private val KEY_SHAPE = RoundedCornerShape(KEY_CORNER)
private val GLYPH_SIZE = 18.dp
private val LETTER_TEXT_SIZE = 22.sp
private const val DISABLED_ALPHA = 0.4f
private const val PRESSED_DARKEN = 0.25f

private const val LETTERS_TOP = "qwertyuiop"
private const val LETTERS_MIDDLE = "asdfghjkl"
private const val LETTERS_BOTTOM = "zxcvbnm"
private const val SYMBOLS_TOP = "1234567890"
private const val SYMBOLS_MIDDLE = "@#$%&*()-+"
private const val SYMBOLS_BOTTOM = "!?_'\"/\\:;"

/**
 * In-app keyboard for secret entry. Keys are handled here, so the password never reaches the
 * system IME (no suggestions, no IME logging, no clipboard). There are no key-press popups and no
 * every key gives the same haptic tap on press (see [rememberKeyHaptics]) and no sound, so nothing
 * differs per key and nothing shows which key was pressed. A pressed key only darkens its own
 * background.
 *
 * Keys are rounded rectangles that share the full width of the screen. Every row is weighted, so
 * the keys stretch to the available width at any phone size. The letter rows follow the usual
 * phone grid: the `asdf` row is inset by half a key on each side, and the bottom row of letters has
 * shift and backspace at 1.5 key widths. The bottom row is `123`, `,`, SPACE, `.` and done.
 *
 * Shift is one-shot: a tap makes only the next letter uppercase, and then shift turns itself off. A
 * second tap on shift turns it off early. There is no caps lock (see [OneShotShift]). The `123` key
 * switches to the digits and symbols page, and `ABC` returns to letters. Once a password is held by
 * the caller, the caller decides what [onDone] means (next step, submit, or unlock).
 */
@Composable
fun SecureKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var page by rememberSaveable { mutableStateOf(KeyboardPage.Letters) }
    val shift = rememberSaveable(saver = OneShotShiftSaver) { OneShotShift() }
    val lettersPage = page == KeyboardPage.Letters

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        // Every character key goes through shift.type, and only a letter spends shift. A letter's
        // label is cased at composition, but its tap reads shift when it fires, so a Shift and a
        // letter tapped in the same frame still use the current case.
        if (lettersPage) {
            KeyRow {
                for (base in LETTERS_TOP) {
                    CharKey(shift.labelFor(base), Modifier.weight(1f), enabled) {
                        onKey(shift.type(base))
                    }
                }
            }
            KeyRow(inset = true) {
                for (base in LETTERS_MIDDLE) {
                    CharKey(shift.labelFor(base), Modifier.weight(1f), enabled) {
                        onKey(shift.type(base))
                    }
                }
            }
            KeyRow {
                ShiftKey(Modifier.weight(1.5f), shift.isArmed, enabled) { shift.tapShift() }
                for (base in LETTERS_BOTTOM) {
                    CharKey(shift.labelFor(base), Modifier.weight(1f), enabled) {
                        onKey(shift.type(base))
                    }
                }
                BackspaceKey(Modifier.weight(1.5f), enabled, onBackspace)
            }
        } else {
            KeyRow {
                for (c in SYMBOLS_TOP) {
                    CharKey(c, Modifier.weight(1f), enabled) { onKey(shift.type(c)) }
                }
            }
            KeyRow {
                for (c in SYMBOLS_MIDDLE) {
                    CharKey(c, Modifier.weight(1f), enabled) { onKey(shift.type(c)) }
                }
            }
            KeyRow {
                for (c in SYMBOLS_BOTTOM) {
                    CharKey(c, Modifier.weight(1f), enabled) { onKey(shift.type(c)) }
                }
                BackspaceKey(Modifier.weight(1.5f), enabled, onBackspace)
            }
        }

        KeyRow {
            Key(
                description = if (lettersPage) "Symbols" else "Letters",
                background = LenslySurfaceHigh,
                enabled = enabled,
                onClick = { page = if (lettersPage) KeyboardPage.Symbols else KeyboardPage.Letters },
                modifier = Modifier.weight(1.5f),
            ) {
                Text(
                    text = if (lettersPage) "123" else "ABC",
                    style = MaterialTheme.typography.labelLarge,
                    color = LenslyOnSurface,
                )
            }
            CharKey(',', Modifier.weight(1f), enabled) { onKey(shift.type(',')) }
            Key(
                description = "Space",
                background = LenslySurfaceHigh,
                enabled = enabled,
                onClick = { onKey(shift.type(' ')) },
                modifier = Modifier.weight(5f),
            ) {
                Text(
                    text = "SPACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = LenslyOnSurface,
                )
            }
            CharKey('.', Modifier.weight(1f), enabled) { onKey(shift.type('.')) }
            DoneKey(Modifier.weight(1.5f), enabled, onDone)
        }
    }
}

/** The digit row of [SecureHexKeyboard]. With [HEX_LETTER_ROW] it holds every hex digit. */
internal const val HEX_DIGIT_ROW = "1234567890"

/** The letter row of [SecureHexKeyboard]: the letters a to f, and x, which is the second character of a 0x prefix. */
internal const val HEX_LETTER_ROW = "abcdefx"

/**
 * In-app keyboard for hex secrets, such as a private key. It holds the digits, the letters a to f, and x for a leading
 * 0x, and it has no page to switch to, so a 64-digit key is typed on one screen. As in [SecureKeyboard], the keys are
 * handled here, so no system IME opens. The caller decides what [onDone] means.
 */
@Composable
fun SecureHexKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        KeyRow {
            for (c in HEX_DIGIT_ROW) {
                CharKey(c, Modifier.weight(1f), enabled) { onKey(c) }
            }
        }
        // Seven keys sit centred under the ten above them, with a space of 1.5 keys on each side.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ROW_PADDING),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1.5f))
            for (c in HEX_LETTER_ROW) {
                CharKey(c, Modifier.weight(1f), enabled) { onKey(c) }
            }
            Spacer(Modifier.weight(1.5f))
        }
        KeyRow {
            Spacer(Modifier.weight(2f))
            BackspaceKey(Modifier.weight(3f), enabled, onBackspace)
            DoneKey(Modifier.weight(3f), enabled, onDone)
            Spacer(Modifier.weight(2f))
        }
    }
}

/**
 * A row of weighted keys with [ROW_PADDING] on each side. With [inset], the row gets a half-key
 * spacer on each side, which centers a nine-key row under the ten-key row above it.
 */
@Composable
private fun KeyRow(inset: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_PADDING),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inset) Spacer(Modifier.weight(0.5f))
        content()
        if (inset) Spacer(Modifier.weight(0.5f))
    }
}

/**
 * Base key: a rounded rectangle of [KEY_HEIGHT]. The caller sets the width through [modifier],
 * usually with a row weight. While pressed, the background darkens. No popup is shown.
 */
@Composable
private fun Key(
    description: String,
    background: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = rememberKeyHaptics()
    val fill = if (pressed && enabled) lerp(background, Color.Black, PRESSED_DARKEN) else background

    Box(
        modifier = modifier
            .height(KEY_HEIGHT)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(KEY_SHAPE)
            .background(fill, KEY_SHAPE)
            .tapHaptic(haptics, enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun CharKey(
    label: Char,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = label.toString(),
        background = LenslySurfaceHigh,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = label.toString(),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SpaceMonoFamily, fontSize = LETTER_TEXT_SIZE),
            color = LenslyOnSurface,
        )
    }
}

@Composable
private fun ShiftKey(
    modifier: Modifier,
    shifted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = if (shifted) "Shift on" else "Shift",
        background = if (shifted) LenslyOnSurface else LenslySurfaceHigh,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        ArrowGlyph(color = if (shifted) LenslyBlack else LenslyOnSurface, filled = shifted)
    }
}

@Composable
private fun BackspaceKey(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = "Backspace",
        background = LenslyAccent,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        BackspaceGlyph(color = LenslyOnSurface)
    }
}

@Composable
private fun DoneKey(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = "Done",
        background = LenslyOnSurface,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        EnterGlyph(color = LenslyBlack)
    }
}

@Composable
private fun ArrowGlyph(color: Color, filled: Boolean) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.12f, h * 0.5f)
            lineTo(w * 0.34f, h * 0.5f)
            lineTo(w * 0.34f, h * 0.88f)
            lineTo(w * 0.66f, h * 0.88f)
            lineTo(w * 0.66f, h * 0.5f)
            lineTo(w * 0.88f, h * 0.5f)
            close()
        }
        val style = if (filled) Fill else Stroke(
            width = w * 0.1f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawPath(path, color, style = style)
    }
}

@Composable
private fun BackspaceGlyph(color: Color) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        val outline = Path().apply {
            moveTo(w * 0.38f, h * 0.18f)
            lineTo(w * 0.92f, h * 0.18f)
            lineTo(w * 0.92f, h * 0.82f)
            lineTo(w * 0.38f, h * 0.82f)
            lineTo(w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(
            outline,
            color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(
            color,
            Offset(w * 0.55f, h * 0.36f),
            Offset(w * 0.79f, h * 0.64f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(w * 0.79f, h * 0.36f),
            Offset(w * 0.55f, h * 0.64f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun EnterGlyph(color: Color) {
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val w = size.width
        val h = size.height
        val style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val hook = Path().apply {
            moveTo(w * 0.86f, h * 0.18f)
            lineTo(w * 0.86f, h * 0.56f)
            lineTo(w * 0.18f, h * 0.56f)
        }
        val head = Path().apply {
            moveTo(w * 0.42f, h * 0.32f)
            lineTo(w * 0.18f, h * 0.56f)
            lineTo(w * 0.42f, h * 0.8f)
        }
        drawPath(hook, color, style = style)
        drawPath(head, color, style = style)
    }
}
