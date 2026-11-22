package app.lumio.ui.asset

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.PriceSeries

private val CHART_HEIGHT: Dp = 120.dp
private val TICK_WIDTH: Dp = 2.dp
private val DIAMOND_HALF: Dp = 4.dp

/** A vertical stroke of a tick chart, at [x], from the canvas height [from] to [to]. */
internal data class Tick(val x: Float, val from: Float, val to: Float)

/**
 * The ticks of [prices] in a box [width] by [height]: one tick per price after the first, spread evenly across the width, each running from the price
 * before it to its own price. The lowest price sits at the bottom and the highest at the top. There are no ticks below two prices.
 */
internal fun ticksOf(prices: List<Double>, width: Float, height: Float): List<Tick> {
    if (prices.size < 2) return emptyList()
    val low = prices.min()
    val span = (prices.max() - low).takeIf { it > 0.0 } ?: 1.0
    fun heightOf(price: Double): Float = height - ((price - low) / span * height).toFloat()
    val step = width / (prices.size - 1)
    return prices.zipWithNext().mapIndexed { index, (before, now) ->
        Tick(x = step * (index + 1), from = heightOf(before), to = heightOf(now))
    }
}

/**
 * The price chart of an asset: a tick for each price of [series], with an outlined diamond on the last point. The ticks take the vault blue when the
 * price rose over the window, and the rose when it fell.
 */
@Composable
fun TickChart(series: PriceSeries, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val tint = if (series.rising) palette.vaultBlue else palette.rose
    Canvas(modifier = modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val ticks = ticksOf(series.points.map { it.usd }, size.width, size.height)
        ticks.forEach { tick ->
            drawLine(
                color = tint,
                start = Offset(tick.x, tick.from),
                end = Offset(tick.x, tick.to),
                strokeWidth = TICK_WIDTH.toPx(),
            )
        }
        ticks.lastOrNull()?.let { last ->
            val half = DIAMOND_HALF.toPx()
            val path = Path().apply {
                moveTo(last.x, last.to - half)
                lineTo(last.x + half, last.to)
                lineTo(last.x, last.to + half)
                lineTo(last.x - half, last.to)
                close()
            }
            drawPath(path = path, color = palette.ink, style = Stroke(width = TICK_WIDTH.toPx()))
        }
    }
}
