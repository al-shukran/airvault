package app.lumio.ui.home

import app.lumio.data.config.Fiat
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.FiatRate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assert.assertEquals as assertFloat
import org.junit.Test

class MarketFormatTest {
    @Test
    fun aTotalInTrillionsIsWrittenToTwoPlacesInTrillions() {
        assertEquals("\$3.41 T", compactUsd(3.41e12, Fiat.USD))
    }

    @Test
    fun aTotalInBillionsIsWrittenInBillions() {
        assertEquals("\$2.62 B", compactUsd(2.62e9, Fiat.USD))
    }

    @Test
    fun aSmallerTotalIsWrittenInFullDollars() {
        assertEquals("\$1,234.50", compactUsd(1_234.5, Fiat.USD))
    }

    @Test
    fun aTotalIsWrittenInTheOwnersCurrency() {
        assertEquals("€1.80 T", compactUsd(3.6e12, Fiat.of(FiatCurrency.EUR, FiatRate(FiatCurrency.EUR, 0.5))))
    }

    @Test
    fun theAgeOfAFigureReadsInTheLargestWholeUnit() {
        assertEquals("8 s ago", agoText(8_000L))
        assertEquals("3 min ago", agoText(180_000L))
        assertEquals("3 h ago", agoText(3 * 3_600_000L))
        assertEquals("2 d ago", agoText(2 * 86_400_000L))
        assertEquals("0 s ago", agoText(-5_000L))
    }

    @Test
    fun aLiveFigureSaysItIsLiveAndAKeptOneSaysItIsLastKnown() {
        assertEquals("Live · updated 8 s ago", freshnessOf(asOfMs = 1_000L, stale = false, nowMs = 9_000L))
        assertEquals("Last known · updated 8 s ago", freshnessOf(asOfMs = 1_000L, stale = true, nowMs = 9_000L))
    }

    @Test
    fun aChangeIsWrittenWithItsDirectionAndItsSizeToTwoPlaces() {
        assertEquals("▲ 1.82%", changeText(1.82))
        assertEquals("▼ 2.41%", changeText(-2.41))
    }

    @Test
    fun aSeriesWithFewerThanTwoPointsHasNoTickGeometry() {
        assertNull(tickGeometry(emptyList(), width = 100f, height = 100f))
        assertNull(tickGeometry(listOf(1.0), width = 100f, height = 100f))
    }

    @Test
    fun eachStepIsAStrokeBetweenItsTwoHeightsAndTheLastPointIsNow() {
        val geometry = checkNotNull(tickGeometry(listOf(1.0, 3.0), width = 100f, height = 100f))

        assertEquals(1, geometry.ticks.size)
        assertFloat(96f, geometry.ticks[0].x, 0.001f)
        assertFloat(4f, geometry.ticks[0].top, 0.001f)
        assertFloat(96f, geometry.ticks[0].bottom, 0.001f)
        assertFloat(96f, geometry.nowX, 0.001f)
        assertFloat(4f, geometry.nowY, 0.001f)
    }

    @Test
    fun aRisingSeriesGivesOneStrokePerStepInTimeOrder() {
        val geometry = checkNotNull(tickGeometry(listOf(1.0, 2.0, 3.0), width = 100f, height = 100f))

        assertFloat(50f, geometry.ticks[0].x, 0.001f)
        assertFloat(50f, geometry.ticks[0].top, 0.001f)
        assertFloat(96f, geometry.ticks[0].bottom, 0.001f)
        assertFloat(96f, geometry.ticks[1].x, 0.001f)
        assertFloat(4f, geometry.ticks[1].top, 0.001f)
        assertFloat(50f, geometry.ticks[1].bottom, 0.001f)
    }

    @Test
    fun aFlatSeriesSitsOnTheMiddleLine() {
        val geometry = checkNotNull(tickGeometry(listOf(5.0, 5.0, 5.0), width = 100f, height = 100f))

        geometry.ticks.forEach { tick ->
            assertFloat(50f, tick.top, 0.001f)
            assertFloat(50f, tick.bottom, 0.001f)
        }
        assertFloat(50f, geometry.nowY, 0.001f)
    }
}
