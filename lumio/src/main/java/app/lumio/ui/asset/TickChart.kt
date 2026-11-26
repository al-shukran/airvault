package app.lumio.ui.asset

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.PriceSeries

/** The chart's box as the mock draws it: 120 dp high, with the ticks 6 dp inside its top and bottom. */
private val CHART_HEIGHT: Dp = 120.dp
private val CHART_PAD_Y: Dp = 6.dp

/** The ticks run from 1 dp inside the left edge to 9 dp inside the right one, which leaves room for the diamond. */
private val CHART_PAD_START: Dp = 1.dp
private val CHART_PAD_END: Dp = 9.dp
private val TICK_WIDTH: Dp = 1.7.dp

/** The side of the last point's diamond, a square turned 45 degrees. */
private val DIAMOND_SIDE: Dp = 8.dp

/** A vertical stroke of a tick chart, at [x], from the canvas height [from] to [to]. */
internal data class Tick(val x: Float, val from: Float, val to: Float)

/**
 * The ticks of [prices] in a box [width] by [height]: one tick per price after the first, spread evenly from the left edge to the right one, each
 * running from the price before it to its own price. The lowest price sits at the bottom and the highest at the top. There are no ticks below two prices.
 */
internal fun ticksOf(prices: List<Double>, width: Float, height: Float): List<Tick> {
    if (prices.size < 2) return emptyList()
    val low = prices.min()
    val span = (prices.max() - low).takeIf { it > 0.0 } ?: 1.0
    fun heightOf(price: Double): Float = height - ((price - low) / span * height).toFloat()
    val step = if (prices.size > 2) width / (prices.size - 2) else 0f
    return prices.zipWithNext().mapIndexed { index, (before, now) ->
        Tick(x = step * index, from = heightOf(before), to = heightOf(now))
    }
}

/**
 * The price chart of an asset: a tick for each price of [series], with a solid spark diamond on the last point. The ticks take the vault blue when the
 * price rose over the window, and the rose when it fell.
 */
@Composable
fun TickChart(series: PriceSeries, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val tint = if (series.rising) palette.vaultBlue else palette.rose
    Canvas(modifier = modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val left = CHART_PAD_START.toPx()
        val top = CHART_PAD_Y.toPx()
        val ticks = ticksOf(
            prices = series.points.map { it.usd },
            width = size.width - left - CHART_PAD_END.toPx(),
            height = size.height - 2 * top,
        )
        ticks.forEach { tick ->
            drawLine(
                color = tint,
                start = Offset(left + tick.x, top + tick.from),
                end = Offset(left + tick.x, top + tick.to),
                strokeWidth = TICK_WIDTH.toPx(),
                cap = StrokeCap.Round,
            )
        }
        ticks.lastOrNull()?.let { last ->
            val half = DIAMOND_SIDE.toPx() / 2
            withTransform({ rotate(degrees = 45f, pivot = Offset(left + last.x, top + last.to)) }) {
                drawRect(color = palette.spark, topLeft = Offset(left + last.x - half, top + last.to - half), size = Size(half * 2, half * 2))
            }
        }
    }
}
