package app.lumio.ui.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design gate's own regression tests, on drawn images, so no screen, no Robolectric and no Chrome are needed. Each case names a kind of
 * difference the owner has found in a shipped screen, and fails if the gate stops seeing it. The frame is a 100 x 100 dp page of Ink
 * (#151317) with one 40 dp disc in the corner, the "circle container behind a logo", filled #2A262E with a 1 dp border of #36313B, as the dark
 * Asset frame draws the Ethereum logo's disc.
 */
class DesignGateCalibrationTest {
    private val ink = 0x151317
    private val fill = 0x2A262E
    private val border = 0x36313B

    private val spec: JsonObject = Json.parseToJsonElement(
        """{"inkThreshold":12,"bandGap":1,"width":100,"height":100,"themes":{"dark":{"bg":[21,19,23],"bands":[],"elements":[
            {"id":"e0","kind":"box","node":"div.lg.big","rect":{"x":20,"y":20,"w":40,"h":40},"fill":[42,38,46,1],"border":{"width":1,"color":[54,49,59,1]},"radius":"50%","shadow":null}
        ]}}}""",
    ).jsonObject

    private val tol = Tolerance(
        Json.parseToJsonElement(
            """{"bandDp":1,"textPosDp":2,"fontSizeDp":0.25,"lineHeightDp":0.5,"letterSpacingDp":0.15,"textColor":4,"sample":8,"cell":96,"cellDp":4,"ink":40,"iconColor":40}""",
        ).jsonObject,
    )

    /** A page with the disc as [fill] with a [border] ring, an optional [ring] of [ringWidth] dp outside it, and an optional soft shadow under it. */
    private fun page(fill: Int?, border: Int?, ring: Int? = null, ringWidth: Int = 2, shadow: Boolean = false): Px {
        val rgb = IntArray(100 * 100) { ink }
        fun disc(radius: Double, color: Int) {
            for (y in 0 until 100) for (x in 0 until 100) {
                val dx = x + 0.5 - 40
                val dy = y + 0.5 - 40
                if (dx * dx + dy * dy <= radius * radius) rgb[y * 100 + x] = color
            }
        }
        if (shadow) for (r in 26 downTo 21) disc(r.toDouble(), 0x201D24)
        if (ring != null) disc(20.0 + ringWidth, ring)
        if (border != null) disc(20.0, border)
        if (fill != null) disc(if (border != null) 19.0 else 20.0, fill)
        return Px(100, 100, rgb)
    }

    private fun findings(actual: Px) = Comparison("calibration", spec, spec.getValue("themes").jsonObject.getValue("dark").jsonObject, page(fill, border), actual, emptyList(), emptyList(), tol).run().findings

    @Test
    fun theSameDiscPasses() {
        assertEquals(emptyList<String>(), findings(page(fill, border)).map { it.label })
    }

    @Test
    fun aDiscWithAnotherFillIsSeen() {
        val got = findings(page(0x3A3440, border))
        assertTrue("a disc filled #3A3440 for #2A262E must fail: $got", got.any { it.layer == "box" && it.diffs.any { d -> d.first.startsWith("fill") } })
    }

    @Test
    fun aDiscThatIsMissingIsSeen() {
        val got = findings(page(null, null))
        assertTrue(got.any { it.layer == "box" && it.diffs.any { d -> d.first.startsWith("fill") && d.third == hex(ink) } })
    }

    @Test
    fun aMissingBorderIsSeen() {
        val got = findings(page(fill, null))
        assertTrue("a disc without its border must fail: $got", got.any { it.layer == "box" && it.diffs.any { d -> d.first.startsWith("border") } })
    }

    @Test
    fun anExtraRingIsSeen() {
        val got = findings(page(fill, border, ring = 0xFFFFFF))
        assertTrue("a white ring around the disc must fail: $got", got.any { it.layer == "box" && it.diffs.any { d -> d.first.startsWith("outside") } })
    }

    @Test
    fun aShadowIsSeen() {
        val got = findings(page(fill, border, shadow = true))
        assertTrue("a shadow under the disc must fail: $got", got.any { it.layer == "box" && it.diffs.any { d -> d.first.startsWith("outside") } })
    }

    @Test
    fun aDiscDrawnTwoDpAwayIsSeen() {
        val moved = IntArray(100 * 100) { ink }
        val base = page(fill, border)
        for (y in 0 until 100) for (x in 2 until 100) moved[y * 100 + x] = base.at(x - 2, y)
        assertTrue(findings(Px(100, 100, moved)).isNotEmpty())
    }
}
