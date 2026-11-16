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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lumio.data.price.PriceSeries
import app.lumio.home.HomeFormat

private val CHART_HEIGHT: Dp = 160.dp
private val LINE_WIDTH: Dp = 2.dp

/**
 * A price chart: a line through the points of [series], in green when the price ended at or above where it began and red when below. Its least and
 * greatest prices are labelled above it. A series with one point draws a dot. A series with no points draws nothing, since the page shows its own
 * empty state for that.
 */
@Composable
fun AssetChart(series: PriceSeries, modifier: Modifier = Modifier) {
    val low = series.low ?: return
    val high = series.high ?: return
    val color = if (series.rising) LenslySuccess else LenslyError
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
                drawCircle(color = color, radius = width, center = points.single())
            } else if (points.size > 1) {
                val path = Path()
                points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(
                    path = path,
                    color = color,
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
