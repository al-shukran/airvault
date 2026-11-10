package app.lensly.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySurfaceHigh

private enum class KeyboardPage { Letters, Symbols }

private val KEY_GAP = 6.dp
private val ROW_GAP = 10.dp
private val MAX_KEY_SIZE = 44.dp
private val GLYPH_SIZE = 18.dp
private val KEY_SHAPE = RoundedCornerShape(percent = 50)
private const val DISABLED_ALPHA = 0.4f

private const val LETTERS_TOP = "qwertyuiop"
private const val LETTERS_MIDDLE = "asdfghjkl"
private const val LETTERS_BOTTOM = "zxcvbnm"
private const val SYMBOLS_TOP = "1234567890"
private const val SYMBOLS_MIDDLE = "@#$%&*()-+"
private const val SYMBOLS_BOTTOM = "!?_'\"/\\:;"

/**
 * In-app keyboard for secret entry. Keys are handled here, so the password never reaches the
 * system IME (no suggestions, no IME logging, no clipboard). There are no key-press popups and no
 * haptics, so nothing differs per key and nothing shows which key was pressed.
 *
 * Key size is derived from the available width: 10 keys fit one row at any phone width, and the
 * full layout fits a 411dp x 891dp screen without scrolling.
 *
 * Shift is a toggle between lower and upper case on the letters page. The `123` key switches to the
 * digits and symbols page, and `ABC` returns to letters. Once a password is held by the caller, the
 * caller decides what [onDone] means (next step, submit, or unlock).
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
    var shifted by rememberSaveable { mutableStateOf(false) }
    val lettersPage = page == KeyboardPage.Letters

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val keySize = minOf((maxWidth - KEY_GAP * 9f) / 10f, MAX_KEY_SIZE)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            if (lettersPage) {
                KeyRow {
                    for (base in LETTERS_TOP) {
                        val c = base.cased(shifted)
                        CharKey(c, keySize, enabled) { onKey(c) }
                    }
                }
                KeyRow {
                    for (base in LETTERS_MIDDLE) {
                        val c = base.cased(shifted)
                        CharKey(c, keySize, enabled) { onKey(c) }
                    }
                }
                KeyRow {
                    ShiftKey(keySize, shifted, enabled) { shifted = !shifted }
                    for (base in LETTERS_BOTTOM) {
                        val c = base.cased(shifted)
                        CharKey(c, keySize, enabled) { onKey(c) }
                    }
                    BackspaceKey(keySize, enabled, onBackspace)
                }
            } else {
                KeyRow {
                    for (c in SYMBOLS_TOP) CharKey(c, keySize, enabled) { onKey(c) }
                }
                KeyRow {
                    for (c in SYMBOLS_MIDDLE) CharKey(c, keySize, enabled) { onKey(c) }
                }
                KeyRow {
                    for (c in SYMBOLS_BOTTOM) CharKey(c, keySize, enabled) { onKey(c) }
                    BackspaceKey(keySize, enabled, onBackspace)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Key(
                    description = if (lettersPage) "Symbols" else "Letters",
                    background = LenslySurfaceHigh,
                    enabled = enabled,
                    onClick = { page = if (lettersPage) KeyboardPage.Symbols else KeyboardPage.Letters },
                    modifier = Modifier.width(keySize * 1.6f).height(keySize),
                ) {
                    Text(
                        text = if (lettersPage) "123" else "ABC",
                        style = MaterialTheme.typography.labelLarge,
                        color = LenslyOnSurface,
                    )
                }
                CharKey(',', keySize, enabled) { onKey(',') }
                Key(
                    description = "Space",
                    background = LenslySurfaceHigh,
                    enabled = enabled,
                    onClick = { onKey(' ') },
                    modifier = Modifier.weight(1f).height(keySize),
                ) {
                    Text(
                        text = "SPACE",
                        style = MaterialTheme.typography.labelSmall,
                        color = LenslyOnSurface,
                    )
                }
                CharKey('.', keySize, enabled) { onKey('.') }
                DoneKey(keySize, enabled, onDone)
            }
        }
    }
}

private fun Char.cased(shifted: Boolean): Char = if (shifted) uppercaseChar() else this

/** A row of keys centered horizontally, sized to the same key diameter as every other row. */
@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Base key: a fully rounded cap. A square [Modifier.size] gives a circle, and a wider size gives a
 * pill.
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
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(KEY_SHAPE)
            .background(background, KEY_SHAPE)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun CharKey(
    label: Char,
    keySize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = label.toString(),
        background = LenslySurfaceHigh,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(keySize),
    ) {
        Text(
            text = label.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = LenslyOnSurface,
        )
    }
}

@Composable
private fun ShiftKey(
    keySize: Dp,
    shifted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = if (shifted) "Shift on" else "Shift",
        background = if (shifted) LenslyOnSurface else LenslySurfaceHigh,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(keySize),
    ) {
        ArrowGlyph(color = if (shifted) LenslyBlack else LenslyOnSurface, filled = shifted)
    }
}

@Composable
private fun BackspaceKey(
    keySize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = "Backspace",
        background = LenslyAccent,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(keySize),
    ) {
        BackspaceGlyph(color = LenslyOnSurface)
    }
}

@Composable
private fun DoneKey(
    keySize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Key(
        description = "Done",
        background = LenslyOnSurface,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(keySize),
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
