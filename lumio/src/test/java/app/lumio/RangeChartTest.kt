package app.lumio

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.lumio.data.ETH
import app.lumio.data.asset.AssetId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Asset page chart follows its range. Each range change loads that range's chart, and the day range starts on the board's day chart without a read.
 */
@RunWith(RobolectricTestRunner::class)
class RangeChartTest {

    @get:Rule
    val compose = createComposeRule()

    private val asset = AssetId.Native(ETH)
    private val reads = CopyOnWriteArrayList<ChartRange>()

    /** A chart of one point for the day range and seven for the week, so the text shows which range was read. */
    private fun read(range: ChartRange): PriceSeries {
        reads += range
        return seriesOf(if (range == ChartRange.D7) 7 else 1)
    }

    private fun awaitText(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun seriesOf(points: Int) = PriceSeries(List(points) { PricePoint(it.toLong(), 1.0) })

    @Composable
    private fun ChartPoints(range: ChartRange, dayChart: PriceSeries?) {
        val series = rememberRangeChart(asset, range, dayChart) { read(it) }
        Text(text = "${series?.points?.size ?: 0} points")
    }

    @Test
    fun switchingTheRangeLoadsThatRangesChart() {
        var range by mutableStateOf(ChartRange.D1)
        compose.setContent { ChartPoints(range, dayChart = null) }
        awaitText("1 points")

        compose.runOnIdle { range = ChartRange.D7 }

        awaitText("7 points")
        compose.onNodeWithText("1 points").assertDoesNotExist()
        assertEquals(listOf(ChartRange.D1, ChartRange.D7), reads.toList())
    }

    @Test
    fun theDayRangeShowsTheBoardsDayChartWithoutARead() {
        compose.setContent { ChartPoints(ChartRange.D1, dayChart = seriesOf(3)) }

        compose.onNodeWithText("3 points").assertExists()
        assertEquals(emptyList<ChartRange>(), reads.toList())
    }
}
