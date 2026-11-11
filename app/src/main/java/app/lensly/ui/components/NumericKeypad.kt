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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

private val KEY_GAP = 4.dp
private val ROW_GAP = 8.dp
private val KEY_HEIGHT = 54.dp
private val KEY_SHAPE = RoundedCornerShape(10.dp)
private val GLYPH_SIZE = 18.dp
private val DIGIT_TEXT_SIZE = 22.sp
private const val DISABLED_ALPHA = 0.4f
private const val PRESSED_DARKEN = 0.25f

/**
 * Numeric keypad for a count: the digits 0 to 9 and a backspace key. It is the numeric variant of [SecureKeyboard].
 * Keys are handled here, so no system IME opens and a typed count never reaches the keyboard's suggestion or
 * clipboard services. The keys share the shape, colors, haptic tap and press feedback of [SecureKeyboard].
 *
 * The layout is a phone dial pad: 1 2 3, 4 5 6, 7 8 9, then a gap, 0, and backspace.
 */
@Composable
fun NumericKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        for (row in listOf("123", "456", "789")) {
            KeyRow {
                for (digit in row) DigitKey(digit, Modifier.weight(1f), enabled, onDigit)
            }
        }
        KeyRow {
            Spacer(Modifier.weight(1f))
            DigitKey('0', Modifier.weight(1f), enabled, onDigit)
            BackspaceKey(Modifier.weight(1f), enabled, onBackspace)
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun DigitKey(digit: Char, modifier: Modifier, enabled: Boolean, onDigit: (Char) -> Unit) {
    NumericKey(
        description = digit.toString(),
        background = LenslySurfaceHigh,
        enabled = enabled,
        onClick = { onDigit(digit) },
        modifier = modifier,
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SpaceMonoFamily, fontSize = DIGIT_TEXT_SIZE),
            color = LenslyOnSurface,
        )
    }
}

@Composable
private fun BackspaceKey(modifier: Modifier, enabled: Boolean, onBackspace: () -> Unit) {
    NumericKey(
        description = "Backspace",
        background = LenslyAccent,
        enabled = enabled,
        onClick = onBackspace,
        modifier = modifier,
    ) {
        BackspaceGlyph(color = LenslyOnSurface)
    }
}

/**
 * A rounded key of [KEY_HEIGHT]. While pressed, its background darkens. No popup is shown, and the key gives the same
 * haptic tap as every other key.
 */
@Composable
private fun NumericKey(
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
    val fill = if (pressed && enabled) lerp(background, LenslyBlack, PRESSED_DARKEN) else background

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

/** The same backspace glyph as [SecureKeyboard]: an outlined tag with a cross inside it. */
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
