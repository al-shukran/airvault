package app.lumio.ui.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.rejectOf

/** The side of a step diamond, and the width of the line between two of them. */
private val DIAMOND = 9.dp
private val THREAD_LINE = 1.5.dp

/** How far the current step's halo spreads past its diamond. */
private val HALO = 4.dp

/**
 * The step thread of a send: [steps] diamonds on a line. The steps before [current] are ink, the current one is the amber spark with a soft halo, and
 * the ones after it are hollow. The line is ink up to the current step.
 */
@Composable
internal fun SendStepper(current: Int, steps: Int = SEND_STEPS, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val halo = palette.spark.copy(alpha = 0.25f)
    Canvas(modifier = modifier.fillMaxWidth().padding(horizontal = STEPPER_INSET).height(DIAMOND)) {
        val box = DIAMOND.toPx()
        val line = THREAD_LINE.toPx()
        val y = size.height / 2f
        val span = size.width - box
        val centre = { step: Int -> box / 2f + span * step / (steps - 1) }
        for (step in 0 until steps - 1) {
            drawLine(
                color = if (step < current) palette.ink else palette.hairline,
                start = Offset(centre(step) + box / 2f, y),
                end = Offset(centre(step + 1) - box / 2f, y),
                strokeWidth = line,
            )
        }
        for (step in 0 until steps) {
            val centreX = centre(step)
            val half = box / 2f
            rotate(degrees = 45f, pivot = Offset(centreX, y)) {
                when {
                    step < current -> drawRect(color = palette.ink, topLeft = Offset(centreX - half, y - half), size = Size(box, box))
                    step == current -> {
                        val spread = HALO.toPx()
                        drawRect(color = halo, topLeft = Offset(centreX - half - spread, y - half - spread), size = Size(box + 2 * spread, box + 2 * spread))
                        drawRect(color = palette.spark, topLeft = Offset(centreX - half, y - half), size = Size(box, box))
                    }
                    else -> drawRect(
                        color = palette.ink3,
                        topLeft = Offset(centreX - half + line / 2f, y - half + line / 2f),
                        size = Size(box - line, box - line),
                        style = Stroke(width = line),
                    )
                }
            }
        }
    }
}

/** The thread starts 6 dp inside the page gutter, as the mock's 26 dp does. */
private val STEPPER_INSET = 6.dp

/** The five steps of a send are shown on every send page. */
internal const val SEND_STEPS = 5

/** The words under the step thread: the step's name in the amber text colour, then "· 2 OF 5". */
@Composable
internal fun SendStepLabel(name: String, number: Int, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = palette.sparkText, fontWeight = FontWeight.Bold)) { append(name.uppercase()) }
            append(" · $number OF $SEND_STEPS")
        },
        modifier = modifier.fillMaxWidth().padding(top = ClearAirSpacing.xs),
        style = ClearAirType.eyebrow.copy(fontSize = 10.5.sp, lineHeight = 21.sp, letterSpacing = 0.09.em),
        color = palette.ink2,
        textAlign = TextAlign.Center,
    )
}

/** The page title of a send step: a 26 sp heading, [top] below what is above it and [bottom] above what follows. */
@Composable
internal fun SendHeading(text: String, top: Dp, bottom: Dp, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = text,
        modifier = modifier.padding(top = top, bottom = bottom),
        style = if (small) ClearAirType.titleXL.copy(fontSize = 22.sp, lineHeight = 27.sp) else ClearAirType.titleXL,
        color = LocalClearAirPalette.current.ink,
    )
}

/** Lays the content out across the gutter of the send page, so a list row is full-bleed as every list row is. */
internal fun Modifier.fullBleed(): Modifier = layout { measurable, constraints ->
    val gutter = ClearAirSpacing.gutter.roundToPx()
    val width = constraints.maxWidth + 2 * gutter
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
}

/** A group heading with a plain note at its end, such as "ADDRESS BOOK … from Lensly". */
@Composable
internal fun SendGroupHeading(text: String, top: Dp, note: String? = null) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = top, bottom = ClearAirSpacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = ClearAirType.eyebrow.copy(letterSpacing = 0.09.em),
            color = palette.ink2,
        )
        note?.let { Text(text = it, style = ClearAirType.label.copy(fontWeight = FontWeight.Normal), color = palette.ink2) }
    }
}

/**
 * A fact of a send as a label at the start and its [value] at the end, over a hairline. [text] sets the value in the heading face, as a network or
 * an account is, and otherwise it is a figure in the mono face. [figureSize] sets the value's size when the mock sets it larger. A row with [onClick] ends in a chevron.
 */
@Composable
internal fun SendFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    text: Boolean = false,
    figureSize: Float = 0f,
    hairline: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalClearAirPalette.current
    val base = if (text) ClearAirType.rowTitle.copy(fontSize = 14.sp, lineHeight = 20.sp) else ClearAirType.rowValue.copy(fontSize = 13.5.sp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (hairline) {
                    drawLine(
                        color = palette.hairline,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .then(
                if (onClick != null) {
                    Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.alignByBaseline(),
            style = ClearAirType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = palette.ink2,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f).alignByBaseline(),
            style = if (figureSize > 0f) base.copy(fontSize = figureSize.sp) else base,
            color = palette.ink,
            textAlign = TextAlign.End,
        )
        if (onClick != null) ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink, size = 18.dp)
    }
}

/**
 * The amount of a send, large and centred on the system's decimal keyboard, its [symbol] after it in ink2 and the caret the amber spark. The figure
 * is 46 sp, and shrinks only when a long amount would not fit the width.
 */
@Composable
internal fun SendAmountInput(value: String, onValueChange: (String) -> Unit, symbol: String, error: String?, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val characters = value.length.coerceAtLeast(1) + symbol.length + 1
    val size = (AMOUNT_WIDTH / (characters * AMOUNT_ADVANCE)).coerceIn(AMOUNT_MIN, AMOUNT_SIZE)
    val style = ClearAirType.amountLarge.copy(fontSize = size.sp, lineHeight = (size * AMOUNT_LINE / AMOUNT_SIZE).sp)
    Row(
        modifier = modifier.fillMaxWidth().then(rejectOf(failed = error != null)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(IntrinsicSize.Min),
            singleLine = true,
            textStyle = style.copy(color = palette.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(palette.spark),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(text = "0", style = style, color = palette.ink3)
                    inner()
                }
            },
        )
        Text(text = " $symbol", style = style, color = palette.ink2, maxLines = 1)
    }
}

private const val AMOUNT_SIZE = 46f
private const val AMOUNT_LINE = 52f
private const val AMOUNT_MIN = 24f
private const val AMOUNT_WIDTH = 300f
private const val AMOUNT_ADVANCE = 0.55f
