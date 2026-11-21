package app.lumio.ui.kit

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The allocation's proportions, merges, arcs and palette (section 1.6), as the pure functions the ring draws from. */
class AllocationMathTest {

    private val other = Color(0xFF888888)

    @Test
    fun sharesAreProportionalToValueLargestFirst() {
        val shares = AllocationMath.shares(listOf(slice("sol", 700.0), slice("eth", 6200.0), slice("usdc", 3100.0)), other)

        assertEquals(listOf("eth", "usdc", "sol"), shares.map { it.assetId })
        assertClose(0.62f, shares[0].fraction)
        assertClose(0.31f, shares[1].fraction)
        assertClose(0.07f, shares[2].fraction)
        assertClose(1f, shares.sumOf { it.fraction.toDouble() }.toFloat())
    }

    @Test
    fun assetsBelowTwoPercentMergeIntoOneOtherShareLast() {
        val shares = AllocationMath.shares(listOf(slice("dust-a", 60.0), slice("eth", 9000.0), slice("usdc", 900.0), slice("dust-b", 40.0)), other)

        assertEquals(listOf("eth", "usdc", AllocationMath.OTHER_ID), shares.map { it.assetId })
        assertClose(0.01f, shares.last().fraction)
        assertEquals(other, shares.last().color)
    }

    @Test
    fun anAssetOfExactlyTwoPercentIsKept() {
        val shares = AllocationMath.shares(listOf(slice("a", 98.0), slice("b", 2.0)), other)

        assertEquals(listOf("a", "b"), shares.map { it.assetId })
    }

    @Test
    fun valuelessSlicesTakeNoShare() {
        val shares = AllocationMath.shares(
            listOf(slice("a", 100.0), slice("zero", 0.0), slice("debt", -5.0), slice("nan", Double.NaN), slice("inf", Double.POSITIVE_INFINITY)),
            other,
        )

        assertEquals(listOf("a"), shares.map { it.assetId })
        assertClose(1f, shares.single().fraction)
    }

    @Test
    fun noValueMeansNoShares() {
        assertEquals(emptyList(), AllocationMath.shares(listOf(slice("zero", 0.0), slice("debt", -5.0)), other))
        assertEquals(0.0, AllocationMath.total(listOf(slice("zero", 0.0), slice("debt", -5.0))))
    }

    @Test
    fun oneAssetIsTheWholeRing() {
        val shares = AllocationMath.shares(listOf(slice("eth", 1234.56)), other)

        assertEquals(listOf("eth"), shares.map { it.assetId })
        assertClose(1f, shares.single().fraction)
    }

    @Test
    fun equalValuesOrderByIdAndTheSameIdAddsUp() {
        val shares = AllocationMath.shares(listOf(slice("b", 50.0), slice("a", 50.0), slice("a", 50.0)), other)

        assertEquals(listOf("a", "b"), shares.map { it.assetId })
        assertClose(2f / 3f, shares[0].fraction)
        assertClose(1f / 3f, shares[1].fraction)
    }

    @Test
    fun allSmallAssetsBecomeOneOtherShare() {
        val shares = AllocationMath.shares(List(60) { slice("token-$it", 1.0) }, other)

        assertEquals(listOf(AllocationMath.OTHER_ID), shares.map { it.assetId })
        assertClose(1f, shares.single().fraction)
    }

    @Test
    fun totalIgnoresValuelessSlices() {
        assertEquals(100.0, AllocationMath.total(listOf(slice("a", 100.0), slice("zero", 0.0), slice("nan", Double.NaN))))
    }

    @Test
    fun sharesCoverTheSweepAndEndAtTheSeam() {
        val arcs = arcsOf(listOf(0.5f, 0.3f, 0.2f))

        assertClose(ARC_START, arcs[0].start)
        assertClose(164f, arcs[0].span)
        assertClose(98.4f, arcs[1].span)
        assertClose(65.6f, arcs[2].span)
        assertClose(arcs[0].start + arcs[0].span + AllocationGeometry.GAP, arcs[1].start)
        assertClose(ARC_START + RING_SWEEP, arcs[2].start + arcs[2].span)
    }

    @Test
    fun aSingleShareFillsTheSweepWithoutAGap() {
        val arcs = arcsOf(listOf(1f))

        assertClose(ARC_START, arcs.single().start)
        assertClose(RING_SWEEP, arcs.single().span)
    }

    @Test
    fun aGrowingShareScalesItsGapIn() {
        val arcs = arcsOf(listOf(0.5f, 0.01f))

        // The second share is half of its full two-percent gap, so the gap between the two is two degrees, and the usable sweep is 334.
        assertClose(2f, arcs[1].start - (arcs[0].start + arcs[0].span))
        assertClose(167f, arcs[0].span)
        assertClose(3.34f, arcs[1].span)
    }

    @Test
    fun shareAtFindsTheShareUnderTheAngleOnly() {
        val shares = listOf(share("a", 0.5f), share("b", 0.5f))

        // Share a runs from -48 to 118, the gap is 118 to 122, share b runs from 122 to 288, and the seam is 288 to 312 (that is, -48).
        assertEquals(0, AllocationMath.shareAt(shares, 0f))
        assertEquals(1, AllocationMath.shareAt(shares, 200f))
        assertEquals(0, AllocationMath.shareAt(shares, -260f))
        assertEquals(null, AllocationMath.shareAt(shares, 120f))
        assertEquals(null, AllocationMath.shareAt(shares, -60f))
        assertEquals(null, AllocationMath.shareAt(emptyList(), 0f))
    }

    @Test
    fun everySegmentColourReadsAtThreeToOneOnItsPage() {
        for (dark in listOf(true, false)) {
            val page = if (dark) NIGHT_PAGE else DAY_PAGE
            for (colour in AllocationPalette.ramp(dark) + AllocationPalette.other(dark)) {
                val ratio = contrast(colour, page)
                assertTrue(ratio >= 3.0, "$colour on ${if (dark) "night" else "day"} page is ${"%.2f".format(ratio)}:1, below 3:1")
            }
        }
    }

    @Test
    fun aColourFollowsTheAssetIdAndThePage() {
        assertTrue(AllocationPalette.isDark(NIGHT_PAGE))
        assertTrue(!AllocationPalette.isDark(DAY_PAGE))
        assertEquals(AllocationPalette.colorOf("eth", NIGHT_PAGE), AllocationPalette.colorOf("eth", NIGHT_PAGE))
        assertTrue(AllocationPalette.colorOf("eth", NIGHT_PAGE) in AllocationPalette.ramp(dark = true))
        assertTrue(AllocationPalette.colorOf("eth", DAY_PAGE) in AllocationPalette.ramp(dark = false))
    }

    private fun slice(id: String, usd: Double) = AllocationSlice(id, usd, Color.Red)

    private fun share(id: String, fraction: Float) = AllocationShare(id, fraction, Color.Red)

    /** The start and span of each arc of [fractions], in degrees. */
    private fun arcsOf(fractions: List<Float>): List<Arc> {
        val arcs = ArrayList<Arc>()
        AllocationGeometry.layout(fractions.size, { fractions[it] }) { _, start, span -> arcs.add(Arc(start, span)) }
        return arcs
    }

    private data class Arc(val start: Float, val span: Float)

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 1e-4f, "expected $expected but was $actual")

    /** The WCAG 2.x contrast ratio of two colours, from 1 to 21. */
    private fun contrast(a: Color, b: Color): Double {
        val lighter = maxOf(luminance(a), luminance(b))
        val darker = minOf(luminance(a), luminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        /** The page colours of the Lumen themes (section 1.1): Night and Day backgrounds. */
        val NIGHT_PAGE = Color(0xFF0A0C10)
        val DAY_PAGE = Color(0xFFFBF6EC)
    }
}
