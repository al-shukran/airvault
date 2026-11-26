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

    // The owner's rule: the constants of a frame must match, the variables (a name, a price, a change, a logo) may differ. Here a "word" is a
    // white bar of ink on the Ink page, a "logo" a coloured square, and the frame has a right-held price, a left-held name and a logo.
    private val rule: JsonObject = Json.parseToJsonElement(
        """{"inkThreshold":12,"bandGap":1,"width":100,"height":100,"themes":{"dark":{"bg":[21,19,23],"bands":[],"elements":[
            {"id":"e0","kind":"text","node":"b","path":"","text":"${'$'}60,572.00","rect":{"x":50,"y":10,"w":40,"h":12},"lines":1,"font":{"family":"x","size":15,"weight":600,"stretch":"100%","lineHeight":null,"letterSpacing":0},"color":[255,255,255,1],"var":"row","anchor":"right"},
            {"id":"e1","kind":"text","node":"b","path":"","text":"Bitcoin","rect":{"x":10,"y":40,"w":30,"h":12},"lines":1,"font":{"family":"x","size":15,"weight":600,"stretch":"100%","lineHeight":null,"letterSpacing":0},"color":[255,255,255,1],"var":"row","anchor":"left"},
            {"id":"e2","kind":"text","node":"div.cap","path":"","text":"Send","rect":{"x":10,"y":70,"w":30,"h":12},"lines":1,"font":{"family":"x","size":15,"weight":600,"stretch":"100%","lineHeight":null,"letterSpacing":0},"color":[255,255,255,1]},
            {"id":"e3","kind":"icon","node":"i.lm.L0","path":"","rect":{"x":70,"y":50,"w":20,"h":20},"var":"logo"}
        ]}}}""",
    ).jsonObject


    private fun page(vararg ink: Pair<List<Int>, Int>): Px {
        val rgb = IntArray(100 * 100) { this.ink }
        for ((r, c) in ink) for (y in r[1] until r[1] + r[3]) for (x in r[0] until r[0] + r[2]) rgb[y * 100 + x] = c
        return Px(100, 100, rgb)
    }

    private val white = 0xFFFFFF
    private fun appText(text: String, x: Double, y: Double, w: Double, size: Double = 15.0, color: Int = 0xFFFFFF) =
        AppText(text, x, y, w, 12.0, 1, size, 600, null, 0.0, color, 1.0)

    /** The frame: a 30 dp price held at the right edge 90, a 24 dp name at the left edge 10, "Send" at 10, a logo square at 70,50. */
    private fun frame() = page(listOf(60, 12, 30, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A)

    private fun rule(actual: Px, texts: List<AppText>) =
        Comparison("calibration", rule, rule.getValue("themes").jsonObject.getValue("dark").jsonObject, frame(), actual, texts, emptyList(), tol).run().findings

    private val sameTexts = { listOf(appText("\$1.00", 50.0, 10.0, 40.0), appText("Bitcoin", 10.0, 40.0, 30.0), appText("Send", 10.0, 70.0, 30.0)) }

    @Test
    fun otherWordsInTheVariablesPass() {
        // A shorter price held at the same right edge, a longer name from the same left edge, another logo of the same size and place.
        val app = page(listOf(75, 12, 15, 8) to white, listOf(10, 42, 29, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0x3C5CE0)
        assertEquals(emptyList<String>(), rule(app, sameTexts()).map { it.label })
    }

    @Test
    fun aVariableHeldAtTheWrongEdgeIsSeen() {
        val app = page(listOf(60, 12, 15, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A)
        val got = rule(app, sameTexts())
        assertTrue("a price that moved off its right edge must fail: $got", got.any { it.id == "e0" && it.diffs.any { d -> d.first == "ink right" } })
    }

    @Test
    fun aVariableOnAnotherLineIsSeen() {
        val app = page(listOf(60, 17, 30, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A)
        val got = rule(app, sameTexts())
        assertTrue("a price 5 dp lower must fail: $got", got.isNotEmpty())
    }

    @Test
    fun aVariableThatIsMissingIsSeen() {
        val app = page(listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A)
        val got = rule(app, sameTexts().filter { it.text != "\$1.00" })
        assertTrue("a price that is not shown must fail: $got", got.any { it.id == "e0" })
    }

    @Test
    fun aVariableInAnotherFontOrColourIsSeen() {
        val app = frame()
        val size = rule(app, sameTexts().map { if (it.text == "Bitcoin") appText("Bitcoin", 10.0, 40.0, 30.0, size = 17.0) else it })
        assertTrue("a name at 17 sp for 15 must fail: $size", size.any { it.id == "e1" && it.diffs.any { d -> d.first == "font size" } })
        val colour = rule(app, sameTexts().map { if (it.text == "Bitcoin") appText("Bitcoin", 10.0, 40.0, 30.0, color = 0x808080) else it })
        assertTrue("a name in grey for white must fail: $colour", colour.any { it.id == "e1" && it.diffs.any { d -> d.first == "color" } })
    }

    @Test
    fun aConstantWithOtherWordsIsSeen() {
        val got = rule(frame(), sameTexts().map { if (it.text == "Send") appText("Pay", 10.0, 70.0, 30.0) else it })
        assertTrue("a button that says Pay for Send must fail: $got", got.any { it.id == "e2" } && got.any { it.id == "extra" })
    }

    @Test
    fun aLogoThatIsMissingOrMovedIsSeen() {
        val missing = page(listOf(60, 12, 30, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white)
        assertTrue(rule(missing, sameTexts()).any { it.id == "e3" })
        val moved = page(listOf(60, 12, 30, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(76, 50, 20, 20) to 0xF7931A)
        assertTrue(rule(moved, sameTexts()).any { it.id == "e3" })
    }

    @Test
    fun anExtraPictureBesideAVariableIsSeen() {
        // A picture the frame does not have, away from every word and icon of it, is a region that no element check names.
        val app = page(listOf(60, 12, 30, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A, listOf(40, 88, 12, 8) to white)
        assertTrue(rule(app, sameTexts()).any { it.layer == "region" })
    }
}
