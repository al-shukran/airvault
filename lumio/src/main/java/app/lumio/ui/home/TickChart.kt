package app.lumio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette
import kotlin.math.max
import kotlin.math.min

/** One tick: a vertical stroke at [x] from [top] to [bottom], in the chart's pixels. */
internal data class Tick(val x: Float, val top: Float, val bottom: Float)

/** A tick chart's geometry: its [ticks] in time order, and the point ([nowX], [nowY]) that the diamond marks, which is the last value. */
internal class TickGeometry(val ticks: List<Tick>, val nowX: Float, val nowY: Float)

/** The pixels kept clear at each edge of the chart, so a stroke and the diamond are never cut off. */
private const val INSET = 4f

/**
 * The geometry of a tick chart of [values], in time order, in a [width] by [height] box. Each tick is the stroke between the heights of two neighbouring
 * values, so the chart reads as the price's path made of thin bars, and the last value is the "now" the diamond marks. A flat series sits on the middle
 * line. Fewer than two values have no geometry.
 */
internal fun tickGeometry(values: List<Double>, width: Float, height: Float): TickGeometry? {
    if (values.size < 2) return null
    val low = values.min()
    val high = values.max()
    val step = (width - 2 * INSET) / (values.size - 1)
    val ys = values.map { value ->
        if (high == low) height / 2f else INSET + ((high - value) / (high - low) * (height - 2 * INSET)).toFloat()
    }
    val ticks = (1 until values.size).map { i ->
        Tick(x = INSET + i * step, top = min(ys[i - 1], ys[i]), bottom = max(ys[i - 1], ys[i]))
    }
    return TickGeometry(ticks = ticks, nowX = INSET + (values.size - 1) * step, nowY = ys.last())
}

/**
 * A tick chart: thin vertical strokes that trace a price's path over its [values], with the amber diamond on the last value. It echoes the reference's
 * tick charts, and it has no filled area. The strokes are vault blue when [up] and rose when not. Fewer than two values draw nothing.
 */
@Composable
internal fun TickChart(values: List<Double>, up: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val stroke = if (up) palette.vaultBlue else palette.rose
    Canvas(modifier = modifier) {
        val geometry = tickGeometry(values, size.width, size.height) ?: return@Canvas
        val strokeWidth = 1.7.dp.toPx()
        geometry.ticks.forEach { tick ->
            drawLine(color = stroke, start = Offset(tick.x, tick.top), end = Offset(tick.x, tick.bottom), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
        val r = 4.dp.toPx()
        val now = Path().apply {
            moveTo(geometry.nowX, geometry.nowY - r)
            lineTo(geometry.nowX + r, geometry.nowY)
            lineTo(geometry.nowX, geometry.nowY + r)
            lineTo(geometry.nowX - r, geometry.nowY)
            close()
        }
        drawPath(path = now, color = palette.spark)
    }
}
