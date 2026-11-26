package app.lumio.ui.design

import java.io.File
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

    /** The registry's own tolerance, so a loosened `cell` in design/registry.json fails these tests, which pin what the gate must see. */
    private val tol = Tolerance(Json.parseToJsonElement(File("../design/registry.json").readText()).jsonObject.getValue("tolerance").jsonObject)

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
    private fun appText(text: String, x: Double, y: Double, w: Double, size: Double = 15.0, color: Int? = 0xFFFFFF) =
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
    fun aTextWhoseColourCannotBeReadIsSeen() {
        val got = rule(frame(), sameTexts().map { if (it.text == "Bitcoin") appText("Bitcoin", 10.0, 40.0, 30.0, color = null) else it })
        assertTrue("a name with an unspecified colour must fail, not be skipped: $got", got.any { it.id == "e1" && it.diffs.any { d -> d.first == "color" && d.third == "unspecified" } })
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

    // The owner's rule on data: a logo's image and a chart's line are the data's content and may differ; the container around them (the disc, its
    // border, the fill behind a plot) is a constant and must match. The frame: the disc of the first case with an orange logo square in it, and a
    // plot with a container (#2A262E, border #36313B) holding a chart line.
    private val content: JsonObject = Json.parseToJsonElement(
        """{"inkThreshold":12,"bandGap":1,"width":100,"height":100,"themes":{"dark":{"bg":[21,19,23],"bands":[],"elements":[
            {"id":"e0","kind":"box","node":"div.lg.big","rect":{"x":20,"y":20,"w":40,"h":40},"fill":[42,38,46,1],"border":{"width":1,"color":[54,49,59,1]},"radius":"50%","shadow":null},
            {"id":"e1","kind":"icon","node":"svg","rect":{"x":30,"y":30,"w":20,"h":20},"var":"logo"},
            {"id":"e2","kind":"box","node":"div.plot","rect":{"x":10,"y":70,"w":80,"h":24},"fill":[42,38,46,1],"border":{"width":1,"color":[54,49,59,1]},"radius":"0px","shadow":null},
            {"id":"e3","kind":"icon","node":"svg.tick","rect":{"x":14,"y":74,"w":72,"h":16},"var":"chart"}
        ]}}}""",
    ).jsonObject
    private val orange = 0xF7931A
    private val blue = 0x3C5CE0

    /** The disc with a logo square of [logo] and a plot with a [container] and a one dp line of [line] at [lineY], or none. */
    private fun withContent(discFill: Int = fill, logo: Int? = orange, hole: Boolean = false, container: Int = border, line: Int? = orange, lineY: Int = 82): Px {
        var img = page(discFill, border)
        if (logo != null) img = img.filled(listOf(30.0, 30.0, 20.0, 20.0), logo)
        if (hole) img = img.filled(listOf(36.0, 36.0, 8.0, 8.0), discFill)
        img = img.filled(listOf(10.0, 70.0, 80.0, 24.0), container).filled(listOf(11.0, 71.0, 78.0, 22.0), fill)
        if (line != null) img = img.filled(listOf(16.0, lineY.toDouble(), 68.0, 1.0), line)
        return img
    }

    private fun content(actual: Px) =
        Comparison("calibration", content, content.getValue("themes").jsonObject.getValue("dark").jsonObject, withContent(), actual, emptyList(), emptyList(), tol).run().findings

    @Test
    fun anotherLogoImageAndAnotherChartLineShapeInTheSameContainersPass() {
        val got = content(withContent(logo = blue, hole = true, line = orange, lineY = 78))
        assertEquals(emptyList<String>(), got.map { "${it.layer} ${it.label}" })
    }

    @Test
    fun aDiscWithAnotherFillBehindALogoIsSeen() {
        val got = content(withContent(discFill = 0x3A3440))
        assertTrue("a disc filled #3A3440 for #2A262E must fail even with the logo in place: $got", got.any { it.id == "e0" })
    }

    @Test
    fun aPlotContainerWithAnotherBorderIsSeen() {
        val got = content(withContent(container = 0xFFFFFF))
        assertTrue("a white plot border for #36313B must fail even with a chart line in place: $got", got.any { it.id == "e2" })
    }

    @Test
    fun aLogoOrAChartThatIsMissingIsSeen() {
        assertTrue(content(withContent(logo = null)).any { it.id == "e1" })
        assertTrue(content(withContent(line = null)).any { it.id == "e3" })
    }

    // A panel the frame does not have, as pale as the surfaces of the frames are on their pages (7 to 23 of 255 apart), is a region.
    private val light: JsonObject = Json.parseToJsonElement("""{"inkThreshold":12,"bandGap":1,"width":100,"height":100,"themes":{"light":{"bg":[251,246,236],"bands":[],"elements":[]}}}""").jsonObject

    private fun light(panel: Int?): List<Finding> {
        val page = IntArray(100 * 100) { 0xFBF6EC }.let { Px(100, 100, it) }
        val app = if (panel == null) page else page.filled(listOf(20.0, 30.0, 60.0, 30.0), panel)
        return Comparison("calibration", light, light.getValue("themes").jsonObject.getValue("light").jsonObject, page, app, emptyList(), emptyList(), tol).run().findings
    }

    @Test
    fun aPalePanelOnALightPageIsSeen() {
        // #F2ECE0 on #FBF6EC is the "surface" fill of the light frames on their page: 12 of 255 apart.
        assertEquals(emptyList<String>(), light(null).map { it.label })
        val got = light(0xF2ECE0)
        assertTrue("a panel 12 of 255 off its page must be a region: $got", got.any { it.layer == "region" })
    }

    @Test
    fun aPalePanelOnADarkPageIsSeen() {
        // The dark frames' surface (#1F1C22) on their page (#151317) is 11 of 255 apart.
        val got = findings(page(fill, border).filled(listOf(70.0, 70.0, 20.0, 20.0), 0x1F1C22))
        assertTrue("a panel 11 of 255 off its page must be a region: $got", got.any { it.layer == "region" })
    }

    // A chart's line is the series' own, its colour is not: it is a colour the frame draws a line in or its positive or negative colour. The frame: a
    // plot with an orange line, and a green rise and a red fall arrow with the words of a change (the positive and the negative colour).
    private val chart: JsonObject = Json.parseToJsonElement(
        """{"inkThreshold":12,"bandGap":1,"width":100,"height":100,"themes":{"dark":{"bg":[21,19,23],"bands":[],"elements":[
            {"id":"e0","kind":"icon","node":"svg.spk","rect":{"x":10,"y":10,"w":60,"h":20},"var":"chart"},
            {"id":"e1","kind":"text","node":"b","path":"","text":"▲ 1%","rect":{"x":10,"y":50,"w":30,"h":12},"lines":1,"font":{"family":"x","size":15,"weight":600,"stretch":"100%","lineHeight":null,"letterSpacing":0},"color":[76,192,122,1]},
            {"id":"e2","kind":"text","node":"b","path":"","text":"▼ 1%","rect":{"x":10,"y":70,"w":30,"h":12},"lines":1,"font":{"family":"x","size":15,"weight":600,"stretch":"100%","lineHeight":null,"letterSpacing":0},"color":[255,138,128,1]}
        ]}}}""",
    ).jsonObject
    private val green = 0x4CC07A
    private val red = 0xFF8A80

    private fun chartPage(line: Int?, lineY: Int = 20, block: Boolean = false): Px {
        var img = page(listOf(14, 52, 20, 8) to white, listOf(14, 72, 20, 8) to white)
        if (line != null) img = img.filled(listOf(12.0, lineY.toDouble(), 56.0, 1.0), line)
        if (block && line != null) img = img.filled(listOf(12.0, 12.0, 56.0, 16.0), line)
        return img
    }

    private fun chart(actual: Px) =
        Comparison("calibration", chart, chart.getValue("themes").jsonObject.getValue("dark").jsonObject, chartPage(orange), actual, listOf(appText("▲ 1%", 10.0, 50.0, 30.0, color = green), appText("▼ 1%", 10.0, 70.0, 30.0, color = red)), emptyList(), tol).run().findings

    @Test
    fun aChartInTheFramesPositiveNegativeOrOwnColourPasses() {
        for (line in listOf(orange, green, red)) assertEquals("a line of ${hex(line)}", emptyList<String>(), chart(chartPage(line)).map { it.label })
    }

    @Test
    fun aChartInAnotherColourIsSeen() {
        val got = chart(chartPage(blue))
        assertTrue("a blue line for the frame's orange, green or red must fail: $got", got.any { it.id == "e0" && it.diffs.any { d -> d.first == "line colour" } })
    }

    @Test
    fun aBlockInAChartIsSeen() {
        val got = chart(chartPage(green, block = true))
        assertTrue("a green block for a line must fail though its colour is the frame's: $got", got.any { it.id == "e0" && it.diffs.any { d -> d.first == "ink share" } })
    }

    @Test
    fun aLogoThatIsTooSmallIsSeen() {
        // The logo square is 20 dp in the frame; one of 14 dp is 70% of it.
        val got = content(withContent().filled(listOf(30.0, 30.0, 20.0, 20.0), fill).filled(listOf(33.0, 33.0, 14.0, 14.0), orange))
        assertTrue("a logo of 14 dp for 20 must fail: $got", got.any { it.id == "e1" && it.diffs.any { d -> d.first == "picture width" } })
    }

    private fun finding(layer: String, id: String, vararg fields: String, rect: List<Double>? = null, cells: Int = 0) =
        Finding(layer, id, "$layer $id", fields.map { Triple(it, "a", "b") }, rect, cells)

    /** A region of [rect] (x, y, width, height) that holds [cells] cells. */
    private fun region(vararg rect: Double, cells: Int = 100) = finding("region", "region", "average colour", rect = rect.toList(), cells = cells)

    // The baseline: two elements, each recorded for its fields, two regions by their boxes, and one extra text.
    private val baseline = Baseline(
        Json.parseToJsonElement(
            """{"issue":1,"findings":{"text:e1":["color","font size"],"box:e2":["fill, top"]},"regions":[[8,8,40,20,100],[100,100,8,8,4]],"extra":1}""",
        ).jsonObject,
    )

    private val recorded = listOf(
        finding("text", "e1", "color", "font size (15.0)"), finding("box", "e2", "fill, top (3,4)"),
        region(8.0, 8.0, 40.0, 20.0), region(100.0, 100.0, 8.0, 8.0, cells = 4), finding("text", "extra", "text"),
    )

    @Test
    fun theRecordedDifferencesPass() {
        assertEquals(emptyList<String>(), baseline.errors(recorded))
        // The fixed region leaves, the other stays.
        assertEquals(emptyList<String>(), baseline.errors(recorded.filter { it.layer != "region" }.plus(region(100.0, 100.0, 8.0, 8.0, cells = 4))))
    }

    @Test
    fun aNewDifferenceOnAnotherElementIsSeen() {
        val got = baseline.errors(recorded + finding("icon", "e9", "pixels"))
        assertTrue("a new icon difference must fail: $got", got.any { "icon:e9" in it })
    }

    @Test
    fun aNewFieldOnARecordedElementIsSeen() {
        // e1 is recorded for its colour and font size; its letter spacing going wrong is a new difference under the same key.
        val got = baseline.errors(recorded.map { if (it.id == "e1") finding("text", "e1", "color", "font size", "letter spacing") else it })
        assertTrue("a letter spacing difference on e1 must fail: $got", got.any { "text:e1" in it && "letter spacing" in it })
        // A box recorded for its top fill, now wrong at its border too.
        val box = baseline.errors(recorded.map { if (it.id == "e2") finding("box", "e2", "fill, top (3,4)", "border left (5,6)") else it })
        assertTrue("a border difference on e2 must fail: $box", box.any { "box:e2" in it && "border left" in it })
    }

    @Test
    fun aRecordedFieldThatPassesNowIsSeen() {
        val got = baseline.errors(recorded.map { if (it.id == "e1") finding("text", "e1", "color") else it })
        assertTrue("a font size that passes now must leave the baseline: $got", got.any { "text:e1" in it && "font size" in it })
    }

    @Test
    fun moreRegionsOrMoreExtraTextsAreSeen() {
        assertTrue(baseline.errors(recorded + region(8.0, 8.0, 8.0, 8.0, cells = 4)).any { it.startsWith("regions: 3") })
        assertTrue(baseline.errors(recorded + finding("text", "extra", "text")).any { it.startsWith("extra texts: 2") })
    }

    @Test
    fun aBiggerOrMovedRegionIsSeen() {
        val others = recorded.filter { it.layer != "region" } + region(100.0, 100.0, 8.0, 8.0, cells = 4)
        assertEquals(emptyList<String>(), baseline.errors(others + region(8.0, 8.0, 40.0, 20.0)))
        // Bigger, or moved: the same count, so only the box says it.
        for (box in listOf(doubleArrayOf(8.0, 8.0, 48.0, 20.0), doubleArrayOf(24.0, 8.0, 40.0, 20.0), doubleArrayOf(8.0, 40.0, 40.0, 20.0)))
            assertTrue("a region of ${box.toList()} is not in the baseline: ${baseline.errors(others + region(*box))}", baseline.errors(others + region(*box)).any { "is not inside a region" in it })
    }

    @Test
    fun moreCellsInARecordedBoxAreSeen() {
        val others = recorded.filter { it.layer != "region" } + region(100.0, 100.0, 8.0, 8.0, cells = 4)
        val got = baseline.errors(others + region(8.0, 8.0, 40.0, 20.0, cells = 101))
        assertTrue("a region of the recorded box that holds 101 cells for 100 must fail: $got", got.any { "is not inside a region" in it })
    }

    @Test
    fun aRegionACellOffOrSmallerPasses() {
        val others = recorded.filter { it.layer != "region" } + region(100.0, 100.0, 8.0, 8.0, cells = 4)
        assertEquals(emptyList<String>(), baseline.errors(others + region(4.0, 8.0, 44.0, 20.0)))
        assertEquals(emptyList<String>(), baseline.errors(others + region(12.0, 8.0, 20.0, 20.0, cells = 40)))
    }

    @Test
    fun aRecordedDifferenceThatIsGoneIsSeen() {
        val got = baseline.errors(recorded.filter { it.id != "e1" })
        assertTrue("a recorded text difference that passes now must leave the baseline: $got", got.any { "text:e1" in it })
    }

    @Test
    fun anExtraPictureOnAVariablesLineIsSeen() {
        // The price is held at the right edge and may grow to the left, but a picture the frame does not have, on its line, is not the price.
        val app = page(listOf(60, 12, 30, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A, listOf(20, 12, 12, 8) to 0x3C5CE0)
        val got = rule(app, sameTexts())
        assertTrue("a picture left of the price, on its line, must be a region: $got", got.any { it.layer == "region" })
    }

    @Test
    fun aPriceThatGrowsToTheLeftOnItsLineStillPasses() {
        val app = page(listOf(45, 12, 45, 8) to white, listOf(10, 42, 24, 8) to white, listOf(10, 72, 24, 8) to white, listOf(70, 50, 20, 20) to 0xF7931A)
        assertEquals(emptyList<String>(), rule(app, listOf(appText("\$1,000.00", 45.0, 10.0, 45.0), appText("Bitcoin", 10.0, 40.0, 30.0), appText("Send", 10.0, 70.0, 30.0))).map { it.label })
    }

    // A badge beside a logo ("DEFAULT") is copy: a constant, so another word in its place is seen, though the row's name and price may differ.
    private val badge: JsonObject = Json.parseToJsonElement(
        """{"inkThreshold":12,"bandGap":1,"width":100,"height":100,"themes":{"dark":{"bg":[21,19,23],"bands":[],"elements":[
            {"id":"e0","kind":"text","node":"span.dim-badge","path":"","text":"DEFAULT","rect":{"x":10,"y":40,"w":40,"h":12},"lines":1,"font":{"family":"x","size":11,"weight":600,"stretch":"100%","lineHeight":null,"letterSpacing":0},"color":[255,255,255,1]}
        ]}}}""",
    ).jsonObject

    private fun badge(app: Px, texts: List<AppText>) =
        Comparison("calibration", badge, badge.getValue("themes").jsonObject.getValue("dark").jsonObject, page(listOf(10, 42, 40, 8) to white), app, texts, emptyList(), tol).run().findings

    @Test
    fun aBadgeWithAnotherWordIsSeen() {
        assertEquals(emptyList<String>(), badge(page(listOf(10, 42, 40, 8) to white), listOf(appText("DEFAULT", 10.0, 40.0, 40.0, size = 11.0))).map { it.label })
        val got = badge(page(listOf(10, 42, 40, 8) to white), listOf(appText("ACTIVE", 10.0, 40.0, 40.0, size = 11.0)))
        assertTrue("a badge that says ACTIVE for DEFAULT must fail: $got", got.any { it.id == "e0" } && got.any { it.id == "extra" })
    }
}
