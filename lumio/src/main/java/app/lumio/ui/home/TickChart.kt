package app.lumio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One tick: a vertical stroke at [x] from [top] to [bottom], in the chart's pixels. */
internal data class Tick(val x: Float, val top: Float, val bottom: Float)

/** The share of the price that a chart's height spans at least, so a stablecoin's or a fund's day reads flat instead of magnifying noise. */
internal const val CHART_FLOOR = 0.02

/** The space kept clear above and below the ticks, before the first tick and after the last, in dp: the chart's ink spans 47.5 of its 56 dp slot. */
private const val INSET_Y = 2f
private const val INSET_START = 1f
private const val INSET_END = 9.2f

/** The ticks a chart draws for a day, as the mock does: a longer series is sampled down to one more point than this, first and last kept. */
internal const val TICKS = 22

/** The shortest tick, in dp, so a still stretch of the day stays visible. */
private const val MIN_TICK = 1.6f

/**
 * The ticks of a row's chart of [series], in time order, at most [TICKS] of them, in a [width] by [height] box, with [density] pixels to the dp. Each tick is the stroke between two
 * neighbouring values, at least [MIN_TICK] tall around its middle. The height spans at least [CHART_FLOOR] of the middle price, centred on the day's range,
 * so a flat day sits on the middle line. Fewer than two values have no ticks.
 */
internal fun ticksOf(series: List<Double>, width: Float, height: Float, density: Float = 1f): List<Tick> {
    if (series.size < 2) return emptyList()
    val values = if (series.size <= TICKS + 1) series else List(TICKS + 1) { i -> series[(i.toLong() * (series.size - 1) / TICKS).toInt()] }
    val low = values.min()
    val high = values.max()
    val mid = (low + high) / 2
    val span = max(high - low, abs(mid) * CHART_FLOOR).takeIf { it > 0 } ?: 1.0
    val top = INSET_Y * density
    val usable = height - 2 * top
    val ys = values.map { (top + usable * (0.5 - (it - mid) / span)).toFloat() }
    val step = (width - (INSET_START + INSET_END) * density) / (values.size - 2).coerceAtLeast(1)
    val least = MIN_TICK * density
    return (1 until values.size).map { i ->
        var a = min(ys[i - 1], ys[i])
        var b = max(ys[i - 1], ys[i])
        if (b - a < least) {
            val centre = (a + b) / 2
            a = centre - least / 2
            b = centre + least / 2
        }
        Tick(x = INSET_START * density + (i - 1) * step, top = a, bottom = b)
    }
}

/** A row's tick chart: thin vertical strokes that trace the day's [values] in [color], with no fill and no marker. Fewer than two values draw nothing. */
@Composable
internal fun TickChart(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.4.dp.toPx()
        ticksOf(values, size.width, size.height, density).forEach { tick ->
            drawLine(color = color, start = Offset(tick.x, tick.top), end = Offset(tick.x, tick.bottom), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}
