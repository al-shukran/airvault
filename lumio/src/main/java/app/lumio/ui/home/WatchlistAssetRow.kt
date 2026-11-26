package app.lumio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** A row's least height, its logo disc, and the gap after the disc, where the divider starts. */
private val ROW_HEIGHT = 62.dp
private val DISC = 36.dp

/** The slot of a row's tick chart, and the column of its price and change, which every row shares so the numbers line up. */
private val CHART_WIDTH = 56.dp
private val CHART_HEIGHT = 22.dp
private val VALUE_WIDTH = 88.dp

/** The chart sits this far above the row's centre line, as the mock's does, so its ink centres on the price and change beside it. */
private val CHART_LIFT = 0.75.dp

/** The add row's dashed ring: its stroke, its dash and gap, and its plus. */
private val ADD_STROKE = 1.5.dp
private val ADD_DASH = 3.dp
private val ADD_GAP = 1.5.dp
private val ADD_PLUS = 18.dp

/** Every line keeps its whole line height, top and bottom, so a wrapped name grows the row by 20 dp a line, as the mock's does. */
private val untrimmed = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

internal fun TextStyle.whole() = copy(lineHeightStyle = untrimmed)

/** The name, Archivo 114 SemiBold 15 on 20; the ticker, Archivo 92 at 12.5 on 17; the price, JetBrains Mono Medium 14 on 20; the change, Mono 12 on 17, both tracked -0.02 em. */
private val nameStyle get() = ClearAirType.rowTitle.whole()
private val tickerStyle get() = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp).whole()
private val priceStyle get() = ClearAirType.rowValue.whole()
private val changeStyle get() = ClearAirType.time.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em).whole()

/** Which way a 24 h change went, at the two places it is shown to: it picks the colour and the arrow. */
enum class ChangeTone { RISE, FALL, FLAT }

/** The tone of [percent]: a change that rounds to 0.00% is flat. */
internal fun changeTone(percent: Double): ChangeTone {
    val hundredths = (percent * 100).roundToLong()
    return when {
        hundredths > 0 -> ChangeTone.RISE
        hundredths < 0 -> ChangeTone.FALL
        else -> ChangeTone.FLAT
    }
}

/** A 24 h change as Lumio writes it: an arrow for the direction, then the size to two places, such as "▲ 1.82%". A flat change has no arrow: "0.00%". */
internal fun changeText(percent: Double): String {
    val size = "${String.format(Locale.US, "%.2f", abs(percent))}%"
    return when (changeTone(percent)) {
        ChangeTone.RISE -> "▲ $size"
        ChangeTone.FALL -> "▼ $size"
        ChangeTone.FLAT -> size
    }
}

/** The second line of a row: the [ticker], or null when it is the same as the [name], as BNB's is, so the row keeps its one-line height. */
internal fun tickerLine(name: String, ticker: String): String? = ticker.takeIf { it.isNotBlank() && it != name }

/** The colour of a change's text in [tone]: gain green, the kit's red, or grey. */
@Composable
internal fun changeColor(tone: ChangeTone): Color {
    val palette = LocalClearAirPalette.current
    return when (tone) {
        ChangeTone.RISE -> palette.gain
        ChangeTone.FALL -> palette.rose
        ChangeTone.FLAT -> palette.ink2
    }
}

/** The colour of a chart in [tone]: the change's colour, and the glyph grey when flat. */
@Composable
private fun chartColor(tone: ChangeTone): Color = if (tone == ChangeTone.FLAT) LocalClearAirPalette.current.ink3 else changeColor(tone)

/**
 * One asset of a watchlist, as every list of prices draws it: the [logo] disc, the full [name] over its [ticker], the day's tick chart of [ticks] in a
 * 56 dp slot, and the [price] over its 24 h [change] in an 88 dp column on the right. The chart and the numbers are centred on the row. The name is never
 * shortened: a long one wraps and the row grows around it. The ticker line is left out when it is the same as the name. A null [price] or [change] is
 * absent, never zero, and fewer than two [ticks] leave the slot empty. With [prices] false, as on a test network, the chart and the column are left out.
 * A [divider] is the hairline on the row's top edge, from the text to the gutter. A list passes the [valueWidth] of [valueColumnWidth], so a price
 * wider than the column widens it on every row, and no price is ever cut.
 */
@Composable
fun WatchlistAssetRow(
    name: String,
    ticker: String,
    price: String?,
    change: Double?,
    ticks: List<Double>,
    logo: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
    prices: Boolean = true,
    valueWidth: Dp = VALUE_WIDTH,
) {
    WatchRow(onClick = onClick, divider = divider, modifier = modifier, leading = logo, title = name, detail = tickerLine(name, ticker)) {
        if (prices) PriceColumns(price = price, change = change, ticks = ticks, valueWidth = valueWidth)
    }
}

/** The value column of a list: 88 dp, or wider when one of its [prices] or [changes] is, so the column lines up on every row. */
@Composable
fun valueColumnWidth(prices: List<String?>, changes: List<Double?>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = prices.filterNotNull().map { measurer.measure(it, priceStyle, maxLines = 1).size.width } +
        changes.filterNotNull().map { measurer.measure(changeText(it), changeStyle, maxLines = 1).size.width }
    return with(density) { maxOf(VALUE_WIDTH, (widest.maxOrNull() ?: 0).toDp()) }
}

/** The row that ends a watchlist: a dashed disc with a plus, "Add to Watchlist" and what can be added. It opens the add. */
@Composable
fun AddToWatchlistRow(onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    WatchRow(onClick = onClick, divider = divider, modifier = modifier, leading = { DashedDisc() }, title = "Add to Watchlist", detail = "Any coin, token or fund")
}

/** The frame of a watchlist row: at least 62 dp, the gutter on each side, the disc, the two lines, then [trailing]. It scales to 0.98 while pressed. */
@Composable
private fun WatchRow(
    onClick: () -> Unit,
    divider: Boolean,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    title: String,
    detail: String?,
    trailing: @Composable () -> Unit = {},
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    val inset = (ClearAirSpacing.gutter + DISC + ClearAirSpacing.sm).toPx()
                    drawLine(palette.hairline, Offset(inset, 0f), Offset(size.width - ClearAirSpacing.gutter.toPx(), 0f), strokeWidth = 1.dp.toPx())
                }
            }
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(DISC), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = nameStyle, color = palette.ink)
            detail?.let { Text(text = it, style = tickerStyle, color = palette.ink2) }
        }
        trailing()
    }
}

/** The chart slot and the value column of a row. The chart takes the change's colour, or the day's direction when there is no change. */
@Composable
private fun PriceColumns(price: String?, change: Double?, ticks: List<Double>, valueWidth: Dp) {
    val palette = LocalClearAirPalette.current
    val tone = change?.let(::changeTone) ?: ticks.takeIf { it.size >= 2 }?.let { changeTone((it.last() - it.first()) / it.first() * 100) } ?: ChangeTone.FLAT
    Spacer(Modifier.width(ClearAirSpacing.sm))
    TickChart(values = ticks, color = chartColor(tone), modifier = Modifier.offset(y = -CHART_LIFT).size(CHART_WIDTH, CHART_HEIGHT))
    Spacer(Modifier.width(ClearAirSpacing.sm))
    Column(modifier = Modifier.width(valueWidth), horizontalAlignment = Alignment.End) {
        price?.let { Text(text = it, style = priceStyle, color = palette.ink, textAlign = TextAlign.End, maxLines = 1) }
        change?.let { Text(text = changeText(it), style = changeStyle, color = changeColor(changeTone(it)), textAlign = TextAlign.End, maxLines = 1) }
    }
}

/** The add row's disc: a dashed glyph-grey ring around a plus in secondary ink. */
@Composable
private fun DashedDisc() {
    val palette = LocalClearAirPalette.current
    Canvas(Modifier.size(DISC)) {
        val stroke = ADD_STROKE.toPx()
        drawCircle(
            color = palette.ink3,
            radius = (size.minDimension - stroke) / 2,
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(ADD_DASH.toPx(), ADD_GAP.toPx()))),
        )
    }
    ClearAirIcon(glyph = ClearAirGlyph.PLUS, color = palette.ink2, size = ADD_PLUS)
}
