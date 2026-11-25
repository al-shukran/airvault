package app.lumio.ui.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.core.graphics.PathParser
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.receive.monogramOf
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirTone
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

/** One line of a send page's account list: a monogram, the [title], the [description] under it, and a chevron when the row opens something. */
@Composable
internal fun SendRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    hairline: Boolean = false,
    minHeight: Dp = 62.dp,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .drawBehind {
                if (hairline) {
                    drawLine(
                        color = palette.hairline,
                        start = Offset(HAIRLINE_INSET.toPx(), 0f),
                        end = Offset(size.width, 0f),
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
            .padding(vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirMonogram(text = monogramOf(title))
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink)
            Text(text = description, style = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = palette.ink2)
        }
        trailing?.let {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            it()
        }
        if (onClick != null && trailing == null) {
            Spacer(Modifier.width(ClearAirSpacing.xs))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3, size = 16.dp)
        }
    }
}

/** A hairline starts under the title, past the monogram and its gap: 68 dp from the page edge, less the 20 dp gutter. */
private val HAIRLINE_INSET = 48.dp

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

/** A note on the raised layer with an info mark, for what the app chose for the owner. */
@Composable
internal fun SendInfoNote(text: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.raised)
            .padding(horizontal = 14.dp, vertical = ClearAirSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.INFO, color = palette.ink2, size = 18.dp)
        Text(
            text = text,
            style = ClearAirType.label.copy(fontWeight = FontWeight.Normal),
            color = palette.ink,
        )
    }
}

/**
 * The recipient field: a 16 dp outline with its label notched into the top edge, the placeholder in ink3, and a paste and a scan action at the end.
 * The outline is 1.5 dp until the field has focus, and then 2 dp in ink. [error] turns it rose and says why under it.
 */
@Composable
internal fun SendRecipientField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String?,
    onScan: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val clipboard = LocalClipboardManager.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(rejectOf(failed = error != null))
                    .defaultMinSize(minHeight = 56.dp)
                    .border(
                        width = if (focused) 2.dp else 1.5.dp,
                        color = when {
                            error != null -> palette.rose
                            focused -> palette.ink
                            else -> palette.ink3
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                    textStyle = ClearAirType.address.copy(color = palette.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    cursorBrush = SolidColor(palette.ink),
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(text = placeholder, style = ClearAirType.body, color = palette.ink3)
                        inner()
                    },
                )
                if (value.isNotEmpty()) ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Clear", onClick = { onValueChange("") })
                SendIconButton(mark = SendMark.PASTE, label = "Paste") {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let(onValueChange)
                }
                ClearAirIconButton(glyph = ClearAirGlyph.SCAN, label = "Scan", onClick = onScan)
            }
            Text(
                text = label,
                modifier = Modifier
                    .offset(x = 12.dp, y = (-9).dp)
                    .background(palette.background)
                    .padding(horizontal = 5.dp),
                style = ClearAirType.label.copy(fontSize = 11.5.sp, lineHeight = 18.sp),
                color = palette.ink2,
            )
        }
        error?.let {
            Spacer(Modifier.height(ClearAirSpacing.xxs))
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed)
        }
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

/** The marks the send pages draw that the shared glyph set does not have. Each is on the 24 unit grid, as the mock draws them. */
internal enum class SendMark(private val data: List<String>) {
    PASTE(
        listOf(
            "M7.9 5h8.2a2.4 2.4 0 0 1 2.4 2.4v10.7a2.4 2.4 0 0 1-2.4 2.4H7.9a2.4 2.4 0 0 1-2.4-2.4V7.4A2.4 2.4 0 0 1 7.9 5z",
            "M9 5V3.8h6V5M9 11h6M9 14.5h4",
        ),
    ),
    PHONE(
        listOf(
            "M9.1 3h5.8a2.6 2.6 0 0 1 2.6 2.6v12.8a2.6 2.6 0 0 1-2.6 2.6H9.1a2.6 2.6 0 0 1-2.6-2.6V5.6A2.6 2.6 0 0 1 9.1 3z",
            "M10.5 18h3",
        ),
    ),
    SUN(
        listOf(
            "M15.6 12a3.6 3.6 0 1 0-7.2 0a3.6 3.6 0 1 0 7.2 0z",
            "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4",
        ),
    ),
    ;

    val paths by lazy { data.map { PathParser.createPathFromPathData(it).asComposePath() } }
}

/** A [SendMark] in [color] at [size], stroked at 1.6 units with round caps like every Clear Air glyph. */
@Composable
internal fun SendMarkIcon(mark: SendMark, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 24f
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            mark.paths.forEach { path ->
                drawPath(path = path, color = color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A 48 dp icon button in ink that draws a [SendMark]. */
@Composable
private fun SendIconButton(mark: SendMark, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ClearAirSpacing.touchTarget)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SendMarkIcon(mark = mark, color = LocalClearAirPalette.current.ink)
    }
}
