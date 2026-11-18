package app.lumio.ui.asset

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslySpacing
import app.lumio.data.price.PriceSeries
import app.lumio.home.HomeFormat

private val CHART_HEIGHT: Dp = 160.dp
private val LINE_WIDTH: Dp = 2.dp

/** The alpha of the gold fill at the top of the chart. It fades to nothing at the foot (section 2.1, Asset). */
private const val FILL_ALPHA = 0.18f

/**
 * A price chart in Lumen's gold (section 2.1, Asset): a 2 dp line through the points of [series], over a fill that fades from gold at 18% to nothing. Its
 * least and greatest prices are labelled above it. A series with one point draws a dot. A series with no points draws nothing, since the page shows its
 * own empty state for that.
 */
@Composable
fun AssetChart(series: PriceSeries, modifier: Modifier = Modifier) {
    val low = series.low ?: return
    val high = series.high ?: return
    val gold = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Low ${HomeFormat.price(low)}", style = MaterialTheme.typography.labelMedium, color = labelColor)
            Text(text = "High ${HomeFormat.price(high)}", style = MaterialTheme.typography.labelMedium, color = labelColor)
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            val points = chartPoints(series, size.width, size.height)
            val width = LINE_WIDTH.toPx()
            if (points.size == 1) {
                drawCircle(color = gold, radius = width, center = points.single())
            } else if (points.size > 1) {
                val line = Path()
                points.forEachIndexed { index, point ->
                    if (index == 0) line.moveTo(point.x, point.y) else line.lineTo(point.x, point.y)
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, size.height)
                    lineTo(points.first().x, size.height)
                    close()
                }
                drawPath(
                    path = fill,
                    brush = Brush.verticalGradient(
                        colors = listOf(gold.copy(alpha = FILL_ALPHA), gold.copy(alpha = 0f)),
                        startY = 0f,
                        endY = size.height,
                    ),
                )
                drawPath(
                    path = line,
                    color = gold,
                    style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/**
 * Where the points of [series] sit in a box [width] wide and [height] high. Time runs from left to right over the points' order, and the greatest
 * price is at the top. A flat series, whose greatest and least prices are equal, sits in the middle. Empty when [series] has no points.
 */
internal fun chartPoints(series: PriceSeries, width: Float, height: Float): List<Offset> {
    val low = series.low ?: return emptyList()
    val high = series.high ?: return emptyList()
    val count = series.points.size
    val span = high - low
    return series.points.mapIndexed { index, point ->
        val x = if (count == 1) width / 2f else width * index / (count - 1)
        val y = if (span <= 0.0) height / 2f else (height * (1.0 - (point.usd - low) / span)).toFloat()
        Offset(x, y)
    }
}
