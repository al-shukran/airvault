package app.lumio.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.lensly.ui.screenshot.SCREEN_TAG
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The design gate: a registered golden screen is rendered at its approved frame's size, 1 px = 1 dp, and compared with the frame, not with the
 * app's own past output. Off unless the test JVM runs with -DdesignGate=true (`./gradlew ... -PdesignGate`, the `design` lane of CI). The frames
 * and their per-element values are design/frames/, the screen-to-frame table and the tolerance are design/registry.json (tools/design-frames/README.md).
 */
object DesignGate {
    val enabled: Boolean = System.getProperty("designGate") == "true"
    private val designDir = File("../design")
    private val registry: JsonObject by lazy { Json.parseToJsonElement(File(designDir, "registry.json").readText()).jsonObject }

    /** The registry's row for the golden [name], or null when the gate is off or the screen is not registered. */
    fun screen(name: String): GateScreen? {
        if (!enabled) return null
        val row = registry["screens"]?.jsonObject?.get(name)?.jsonObject ?: return null
        return GateScreen(name, row, registry)
    }

    /** True when the gate is on and the golden [name] has no row: the test is skipped, so the lane runs only the registered screens. */
    fun skips(name: String): Boolean = enabled && registry["screens"]?.jsonObject?.containsKey(name) != true

    internal fun frameFile(id: String) = File(designDir, "frames/$id.spec.json")

    internal fun frameDir() = File(designDir, "frames")
}

/** One registered screen: its frame and theme, the bottom inset the frame holds, its masks, and whether it is a known violation. */
class GateScreen(private val name: String, row: JsonObject, registry: JsonObject) {
    private val frameId = row.getValue("frame").jsonPrimitive.content
    private val theme = row.getValue("theme").jsonPrimitive.content
    private val insetBottom = row["insetBottom"]?.jsonPrimitive?.intOrNull ?: 24
    private val masks: List<Mask> = row["masks"]?.jsonArray.orEmpty().map { Mask(it.jsonObject) }
    private val known = registry["known_violations"]?.jsonObject?.get(name)?.jsonObject
    private val tol = Tolerance(registry.getValue("tolerance").jsonObject)
    private val spec = Json.parseToJsonElement(DesignGate.frameFile(frameId).readText()).jsonObject

    /** The size the screen is drawn at: the frame's, minus the mock's status bar, which the phone's system draws. */
    val frame = DpSize(spec.num("width").dp, spec.num("height").dp)

    /** The screen's content, with the gesture area under it that a phone's navigation inset gives the frame. */
    @Composable
    fun Wrap(content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) { content() }
            Spacer(Modifier.height(insetBottom.dp))
        }
    }

    /** Compares the settled screen with its frame, writes the artifacts, and fails on a new violation or on a known one that now passes. */
    fun verify(rule: ComposeContentTestRule) {
        val themeSpec = spec.getValue("themes").jsonObject.getValue(theme).jsonObject
        val expected = Px.of(ImageIO.read(File(DesignGate.frameDir(), themeSpec.getValue("png").jsonPrimitive.content)))
        val root = rule.onNodeWithTag(SCREEN_TAG)
        val origin = root.fetchSemanticsNode().positionInRoot
        val shot = root.captureToImage()
        val buffer = IntArray(shot.width * shot.height)
        shot.readPixels(buffer)
        val actual = Px(shot.width, shot.height, IntArray(buffer.size) { buffer[it] and 0xFFFFFF })
        val texts = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().appTexts(origin.x, origin.y)
        val result = Comparison(name, spec, themeSpec, expected, actual, texts, masks, tol).run()
        result.write(File("build/design-gate"), expected, actual, frameId, theme, known?.get("issue")?.jsonPrimitive?.content)
        val violation = result.findings.isNotEmpty()
        when {
            violation && known == null ->
                throw AssertionError("$name differs from its approved frame $frameId ($theme) in ${result.findings.size} places. Fix the screen; do not loosen the tolerance or add a mask. A new known violation needs the design:approved-change label.\n${result.report(60)}")
            !violation && known != null ->
                throw AssertionError("$name now matches its approved frame $frameId ($theme). Remove it from known_violations in design/registry.json (issue ${known["issue"]}).")
        }
    }
}

internal class Mask(row: JsonObject) {
    val rect = row.getValue("rect").jsonArray.map { it.jsonPrimitive.double() }
    fun contains(x: Double, y: Double) = x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3]
}

internal class Tolerance(row: JsonObject) {
    val bandDp = row.num("bandDp")
    val textPosDp = row.num("textPosDp")
    val fontSizeDp = row.num("fontSizeDp")
    val lineHeightDp = row.num("lineHeightDp")
    val letterSpacingDp = row.num("letterSpacingDp")
    val textColor = row.num("textColor").toInt()
    val sample = row.num("sample").toInt()
    val cell = row.num("cell").toInt()
    val cellDp = row.num("cellDp").toInt()
    val ink = row.num("ink").toInt()
    val iconColor = row.num("iconColor").toInt()
}

/** The app's text, read from the semantics tree: what each Text says, the box of its node, and the style it was laid out with. All in the frame's dp. */
internal class AppText(val text: String, val x: Double, val y: Double, val w: Double, val h: Double, val lines: Int, val size: Double, val weight: Int, val lineHeight: Double?, val letterSpacing: Double, val color: Int?, val colorAlpha: Double) {
    var used = false
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

private fun SemanticsNode.appTexts(originX: Float, originY: Float): List<AppText> {
    val out = mutableListOf<AppText>()
    fun walk(node: SemanticsNode) {
        val said = node.config.getOrNull(SemanticsProperties.Text)
        if (said != null) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.firstOrNull()
            if (layout != null && layout.layoutInput.text.text.isNotBlank()) {
                val style = layout.layoutInput.style
                val density = layout.layoutInput.density
                val size = with(density) { style.fontSize.toPx().toDouble() }
                val spacing = when {
                    style.letterSpacing.isEm -> style.letterSpacing.value * size
                    style.letterSpacing.isSp -> with(density) { style.letterSpacing.toPx().toDouble() }
                    else -> 0.0
                }
                out += AppText(
                    text = layout.layoutInput.text.text,
                    x = node.positionInRoot.x - originX.toDouble(),
                    y = node.positionInRoot.y - originY.toDouble(),
                    w = node.size.width.toDouble(),
                    h = node.size.height.toDouble(),
                    lines = layout.lineCount,
                    size = size,
                    weight = style.fontWeight?.weight ?: 400,
                    lineHeight = if (style.lineHeight.isSpecified) with(density) { style.lineHeight.toPx().toDouble() } else null,
                    letterSpacing = spacing,
                    color = if (style.color.isSpecified) style.color.toArgb() and 0xFFFFFF else null,
                    colorAlpha = if (style.color.isSpecified) style.color.alpha.toDouble() else 1.0,
                )
            }
        }
        node.children.forEach { walk(it) }
    }
    walk(this)
    return out
}

private fun normalize(text: String) = text.replace(' ', ' ').replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

/** An image as 0xRRGGBB, with the access the checks need. */
internal class Px(val w: Int, val h: Int, val rgb: IntArray) {
    fun at(x: Int, y: Int) = rgb[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** The closest colour to [want] among the pixel at ([x], [y]) and its eight neighbours: an edge may sit half a pixel apart. */
    fun nearest(x: Int, y: Int, want: Int): Int {
        var best = at(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val c = at(x + dx, y + dy)
            if (dist(c, want) < dist(best, want)) best = c
        }
        return best
    }

    fun filled(rect: List<Double>, color: Int): Px {
        val copy = rgb.copyOf()
        for (y in max(0, rect[1].toInt())until min(h, (rect[1] + rect[3]).toInt()))
            for (x in max(0, rect[0].toInt())until min(w, (rect[0] + rect[2]).toInt())) copy[y * w + x] = color
        return Px(w, h, copy)
    }

    companion object {
        fun of(image: BufferedImage) = Px(image.width, image.height, IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) and 0xFFFFFF })
    }
}

internal fun dist(a: Int, b: Int) = max(abs((a shr 16 and 255) - (b shr 16 and 255)), max(abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))))
internal fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

private fun JsonObject.num(key: String): Double = getValue(key).jsonPrimitive.double()
private fun JsonPrimitive.double(): Double = doubleOrNull ?: error("not a number: $content")
private fun List<Double>.rgb(bg: Int): Int {
    val a = if (size > 3) this[3] else 1.0
    fun ch(i: Int, shift: Int) = (a * this[i] + (1 - a) * (bg shr shift and 255)).roundToInt()
    return ch(0, 16) shl 16 or (ch(1, 8) shl 8) or ch(2, 0)
}

/** One difference: the element it concerns and, per field, what the frame says and what the screen shows. */
internal class Finding(val layer: String, val id: String, val label: String, val diffs: List<Triple<String, String, String>>, val rect: List<Double>?)

internal class Result(val name: String, val findings: List<Finding>, val rows: List<JsonObject>, val bands: Pair<List<List<Int>>, List<List<Int>>>) {
    fun report(limit: Int): String {
        val lines = findings.take(limit).map { f -> "  [${f.layer}] ${f.label}: " + f.diffs.joinToString("; ") { (field, want, got) -> "$field expected $want, actual $got" } }
        return lines.joinToString("\n") + if (findings.size > limit) "\n  ... ${findings.size - limit} more in build/design-gate/$name.json" else ""
    }

    fun write(dir: File, expected: Px, actual: Px, frame: String, theme: String, issue: String?) {
        dir.mkdirs()
        val json = JsonObject(
            mapOf(
                "screen" to JsonPrimitive(name), "frame" to JsonPrimitive(frame), "theme" to JsonPrimitive(theme),
                "violation" to JsonPrimitive(findings.isNotEmpty()), "issue" to JsonPrimitive(issue),
                "findings" to JsonArray(findings.map { f ->
                    JsonObject(mapOf(
                        "layer" to JsonPrimitive(f.layer), "element" to JsonPrimitive(f.id), "label" to JsonPrimitive(f.label),
                        "diffs" to JsonArray(f.diffs.map { (a, b, c) -> JsonObject(mapOf("field" to JsonPrimitive(a), "expected" to JsonPrimitive(b), "actual" to JsonPrimitive(c))) }),
                    ))
                }),
                "elements" to JsonArray(rows),
                "bands" to JsonObject(mapOf("expected" to JsonPrimitive(bands.first.toString()), "actual" to JsonPrimitive(bands.second.toString()))),
            ),
        )
        File(dir, "$name.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json))
        if (findings.isEmpty()) return
        // The mock, the app and their difference side by side, and a 4 times crop of each element that differs.
        val w = expected.w
        val h = expected.h
        val sheet = BufferedImage(w * 3 + 20, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val e = expected.at(x, y)
            val a = actual.at(x, y)
            sheet.setRGB(x, y, e)
            sheet.setRGB(w + 10 + x, y, a)
            val d = min(255, dist(e, a) * 4)
            sheet.setRGB(2 * w + 20 + x, y, d shl 16 or (d shl 8) or d)
        }
        ImageIO.write(sheet, "png", File(dir, "$name.png"))
        val crops = File(dir, "crops/$name")
        crops.mkdirs()
        findings.filter { it.rect != null }.take(40).forEachIndexed { i, f ->
            val r = f.rect!!
            val x0 = max(0, r[0].toInt() - 6)
            val y0 = max(0, r[1].toInt() - 6)
            val x1 = min(w, (r[0] + r[2]).toInt() + 6)
            val y1 = min(h, (r[1] + r[3]).toInt() + 6)
            if (x1 <= x0 || y1 <= y0) return@forEachIndexed
            val k = 4
            val cw = (x1 - x0) * k
            val ch = (y1 - y0) * k
            val crop = BufferedImage(cw * 2 + 8, ch, BufferedImage.TYPE_INT_RGB)
            val g = crop.createGraphics()
            g.color = Color.MAGENTA
            g.fillRect(0, 0, crop.width, crop.height)
            for ((n, img) in listOf(expected, actual).withIndex())
                for (y in 0 until ch) for (x in 0 until cw) crop.setRGB(n * (cw + 8) + x, y, img.at(x0 + x / k, y0 + y / k))
            g.dispose()
            ImageIO.write(crop, "png", File(crops, "%02d-%s.png".format(i, f.id)))
        }
    }
}

/** The checks of one screen against its frame. */
internal class Comparison(
    val name: String,
    val spec: JsonObject,
    val themeSpec: JsonObject,
    expectedRaw: Px,
    actualRaw: Px,
    val texts: List<AppText>,
    val masks: List<Mask>,
    val tol: Tolerance,
) {
    private val bg = themeSpec.getValue("bg").jsonArray.map { it.jsonPrimitive.double() }.rgb(0xFFFFFF)
    private val inkThreshold = spec.num("inkThreshold").toInt()
    private val bandGap = spec.num("bandGap").toInt()
    private val expected = masks.fold(expectedRaw) { img, m -> img.filled(m.rect, bg) }
    private val actual = masks.fold(actualRaw) { img, m -> img.filled(m.rect, bg) }
    private val findings = mutableListOf<Finding>()
    private val rows = mutableListOf<JsonObject>()

    private fun masked(x: Double, y: Double) = masks.any { it.contains(x, y) }

    private fun row(layer: String, id: String, label: String, diffs: List<Triple<String, String, String>>, rect: List<Double>?) {
        rows += JsonObject(mapOf("layer" to JsonPrimitive(layer), "element" to JsonPrimitive(id), "label" to JsonPrimitive(label), "ok" to JsonPrimitive(diffs.isEmpty()), "diffs" to JsonArray(diffs.map { (a, b, c) -> JsonObject(mapOf("field" to JsonPrimitive(a), "expected" to JsonPrimitive(b), "actual" to JsonPrimitive(c))) })))
        if (diffs.isNotEmpty()) findings += Finding(layer, id, label, diffs, rect)
    }

    fun run(): Result {
        if (actual.w != expected.w || actual.h != expected.h) {
            row("frame", "frame", "frame size", listOf(Triple("size", "${expected.w}x${expected.h}", "${actual.w}x${actual.h}")), null)
            return Result(name, findings, rows, emptyList<List<Int>>() to emptyList())
        }
        val bandsWant = bandsOf(expected)
        val bandsGot = bandsOf(actual)
        checkBands(bandsWant, bandsGot)
        val elements = themeSpec.getValue("elements").jsonArray.map { it.jsonObject }
        // A frame's constants claim their words first; a variable then takes the Text that sits where it does, whatever it says.
        val frameTexts = elements.filter { it.str("kind") == "text" }
        frameTexts.filter { !it.isVariable() }.forEach { checkText(it) }
        frameTexts.filter { it.isVariable() }.forEach { checkVariableText(it) }
        // A Text the frame does not have counts only when it shows: one under the tab bar or past the end of a list leaves no ink.
        texts.filter { !it.used && it.x < actual.w && it.y < actual.h && it.x + it.w > 0 && it.y + it.h > 0 && !masked(it.cx, it.cy) && inkBox(actual, listOf(it.x, it.y, it.w, it.h)) != null }.forEach {
            row("text", "extra", "'${it.text}'", listOf(Triple("text", "absent from the frame", "at ${it.x.f()},${it.y.f()}")), listOf(it.x, it.y, it.w, it.h))
        }
        elements.filter { it.str("kind") == "box" }.forEach { checkBox(it) }
        elements.filter { it.str("kind") == "icon" }.forEach { checkIcon(it) }
        checkRegions(elements)
        return Result(name, findings, rows, bandsWant to bandsGot)
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    /** A variable is what the data decides (a token's name, price or logo): the gate checks that it is there, where, and in what font and colour, not what it says or draws. */
    private fun JsonObject.isVariable() = this["var"]?.jsonPrimitive?.contentOrNull != null
    private fun Double.f() = "%.1f".format(this)

    private fun rectOf(el: JsonObject) = el.getValue("rect").jsonObject.let { listOf(it.num("x"), it.num("y"), it.num("w"), it.num("h")) }

    private fun bandsOf(img: Px): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var start = -1
        var last = -1
        for (y in 0 until img.h) {
            var ink = false
            for (x in 0 until img.w) if (dist(img.at(x, y), bg) > inkThreshold) { ink = true; break }
            if (!ink) continue
            if (start < 0) start = y else if (y - last > bandGap + 1) { out += listOf(start, last + 1); start = y }
            last = y
        }
        if (start >= 0) out += listOf(start, last + 1)
        return out
    }

    private fun checkBands(want: List<List<Int>>, got: List<List<Int>>) {
        if (want.size != got.size) {
            row("band", "bands", "ink bands", listOf(Triple("count", "${want.size} $want", "${got.size} $got")), null)
            return
        }
        want.indices.forEach { i ->
            val diffs = mutableListOf<Triple<String, String, String>>()
            if (abs(want[i][0] - got[i][0]) > tol.bandDp) diffs += Triple("top", "${want[i][0]} dp", "${got[i][0]} dp")
            if (abs(want[i][1] - got[i][1]) > tol.bandDp) diffs += Triple("bottom", "${want[i][1]} dp", "${got[i][1]} dp")
            row("band", "band$i", "ink band ${i + 1} of ${want.size}", diffs, listOf(0.0, want[i][0].toDouble(), actual.w.toDouble(), (want[i][1] - want[i][0]).toDouble()))
        }
    }

    private fun checkText(el: JsonObject) {
        val r = rectOf(el)
        if (masked(r[0] + r[2] / 2, r[1] + r[3] / 2)) return
        val text = normalize(el.str("text"))
        val label = "'$text' (${el.str("node")})"
        val id = el.str("id")
        val cx = r[0] + r[2] / 2
        val cy = r[1] + r[3] / 2
        val match = texts.filter { !it.used && normalize(it.text) == text }.minByOrNull { abs(it.cx - cx) + abs(it.cy - cy) }
        if (match == null) {
            row("text", id, label, listOf(Triple("text", "at ${r[0].f()},${r[1].f()}", "missing")), r)
            return
        }
        match.used = true
        val d = mutableListOf<Triple<String, String, String>>()
        compareInk(el, r, text, d, variable = false)
        compareStyle(el, match, d)
        row("text", id, label, d, r)
    }

    /** A variable Text takes the app's Text on the frame's line, over its place, whatever the two say. Its position is checked at the edge it is held by. */
    private fun checkVariableText(el: JsonObject) {
        val r = rectOf(el)
        if (masked(r[0] + r[2] / 2, r[1] + r[3] / 2)) return
        val anchor = el.str("anchor")
        val label = "${el.str("var")} text (${el.str("node")}) at ${r[0].f()},${r[1].f()}"
        val id = el.str("id")
        val cy = r[1] + r[3] / 2
        val edge = { x: Double, w: Double -> when (anchor) { "right" -> x + w; "center" -> x + w / 2; else -> x } }
        val want = edge(r[0], r[2])
        val match = texts
            .filter { !it.used && abs(it.cy - cy) <= max(r[3] / 2, tol.textPosDp) && it.x < r[0] + r[2] + tol.textPosDp && it.x + it.w > r[0] - tol.textPosDp }
            .minByOrNull { abs(it.cy - cy) + abs(edge(it.x, it.w) - want) }
        if (match == null) {
            row("text", id, label, listOf(Triple("text", "a ${el.str("var")} text at ${r[0].f()},${r[1].f()}", "missing")), r)
            return
        }
        match.used = true
        val d = mutableListOf<Triple<String, String, String>>()
        compareInk(el, r, el.str("text"), d, variable = true)
        compareStyle(el, match, d)
        row("text", id, label, d, r)
    }

    /**
     * Where the words are drawn: the box of their ink, in the frame and in the screen, so alignment and font metrics need no model. A constant is
     * compared on all four edges. A variable is as wide as its words, so only its line (the centre of the ink, up and down) and the edge it is held by
     * (the left edge, the right edge or the centre of the ink) are compared. The rise and fall arrows come from a fallback font, which Chromium on the
     * Mac and Robolectric choose differently: the ink of the arrow is not compared, so a Text that starts with one is compared by its right edge and
     * its line only, a Text that ends with one by its left edge and its line, and a Text with one inside by its line only.
     */
    private fun compareInk(el: JsonObject, r: List<Double>, text: String, d: MutableList<Triple<String, String, String>>, variable: Boolean) {
        val seen = inkBox(expected, r)
        val shown = inkBox(actual, r)
        if (seen == null || shown == null) {
            d += Triple("ink", if (seen == null) "none" else "present", if (shown == null) "none" else "present")
            return
        }
        fun cmp(field: String, want: Double, got: Double) { if (abs(want - got) > tol.textPosDp) d += Triple(field, want.f(), got.f()) }
        val leading = text.firstOrNull()?.let { it == '\u25B2' || it == '\u25BC' } == true
        val trailing = text.lastOrNull()?.let { it == '\u25B2' || it == '\u25BC' } == true
        val anchor = if (variable) el.str("anchor") else "all"
        val arrow = text.any { it == '\u25B2' || it == '\u25BC' }
        if (variable || arrow) {
            cmp("ink line", (seen[1] + seen[3]) / 2, (shown[1] + shown[3]) / 2)
            when {
                arrow && !leading && !trailing -> Unit
                leading -> if (anchor == "right" || anchor == "all") cmp("ink right", seen[2], shown[2])
                trailing -> if (anchor == "left" || anchor == "all") cmp("ink left", seen[0], shown[0])
                anchor == "right" -> cmp("ink right", seen[2], shown[2])
                anchor == "center" -> cmp("ink centre", (seen[0] + seen[2]) / 2, (shown[0] + shown[2]) / 2)
                else -> cmp("ink left", seen[0], shown[0])
            }
        } else {
            cmp("ink left", seen[0], shown[0])
            cmp("ink right", seen[2], shown[2])
            cmp("ink top", seen[1], shown[1])
            cmp("ink bottom", seen[3], shown[3])
        }
    }

    /** The constants of a Text, from the Compose style against the frame's computed CSS: font size, weight, line height, letter spacing and colour. */
    private fun compareStyle(el: JsonObject, match: AppText, d: MutableList<Triple<String, String, String>>) {
        fun cmp(field: String, want: Double, got: Double, t: Double) { if (abs(want - got) > t) d += Triple(field, want.f(), got.f()) }
        val font = el.getValue("font").jsonObject
        cmp("font size", font.num("size"), match.size, tol.fontSizeDp)
        if (font.num("weight").toInt() != match.weight) d += Triple("font weight", "${font.num("weight").toInt()}", "${match.weight}")
        // The line height shows only where lines stack.
        if (el.getValue("lines").jsonPrimitive.double() > 1) font["lineHeight"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.double()?.let { want ->
            if (match.lineHeight == null) d += Triple("line height", want.f(), "unset") else cmp("line height", want, match.lineHeight, tol.lineHeightDp)
        }
        cmp("letter spacing", font.num("letterSpacing"), match.letterSpacing, tol.letterSpacingDp)
        match.color?.let { got ->
            val want = el.getValue("color").jsonArray.map { it.jsonPrimitive.double() }.rgb(bg)
            val shown = ((match.colorAlpha * 255).roundToInt()).let { a -> if (a == 255) got else listOf((got shr 16 and 255).toDouble(), (got shr 8 and 255).toDouble(), (got and 255).toDouble(), match.colorAlpha).rgb(bg) }
            if (dist(want, shown) > tol.textColor) d += Triple("color", hex(want), hex(shown))
        }
    }

    /** The box of the pixels in [rect] (grown by 2 dp) that differ strongly from the rectangle's own border colour, as left, top, right, bottom; null when there are none. */
    private fun inkBox(img: Px, rect: List<Double>): List<Double>? {
        val x0 = max(0, rect[0].toInt() - 2); val y0 = max(0, rect[1].toInt() - 2)
        val x1 = min(img.w, (rect[0] + rect[2]).toInt() + 3); val y1 = min(img.h, (rect[1] + rect[3]).toInt() + 3)
        if (x1 <= x0 || y1 <= y0) return null
        val ring = ArrayList<Int>()
        for (x in x0 until x1) { ring += img.at(x, y0); ring += img.at(x, y1 - 1) }
        for (y in y0 until y1) { ring += img.at(x0, y); ring += img.at(x1 - 1, y) }
        val base = ring.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in y0 until y1) for (x in x0 until x1) if (dist(img.at(x, y), base) > tol.ink) { l = min(l, x); r = max(r, x + 1); t = min(t, y); b = max(b, y + 1) }
        return if (r < 0) null else listOf(l.toDouble(), t.toDouble(), r.toDouble(), b.toDouble())
    }

    /** A box is checked by the pixels around its edge in the frame and in the screen: inside the fill, on the border, and outside it (a ring or a shadow). */
    private fun checkBox(el: JsonObject) {
        val r = rectOf(el)
        if (masked(r[0] + r[2] / 2, r[1] + r[3] / 2)) return
        val id = el.str("id")
        val label = "${el.str("node")} ${r[2].f()}x${r[3].f()} at ${r[0].f()},${r[1].f()}"
        val border = el["border"]?.takeIf { it is JsonObject }?.jsonObject
        val bw = border?.num("width") ?: 0.0
        val d = mutableListOf<Triple<String, String, String>>()
        val x0 = r[0]; val y0 = r[1]; val x1 = r[0] + r[2]; val y1 = r[1] + r[3]
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        val inset = max(2.0, bw + 1.5)
        val inside = mutableListOf<Pair<String, Pair<Double, Double>>>()
        if (r[2] >= 2 * inset + 2) { inside += "top" to (mx to y0 + inset); inside += "bottom" to (mx to y1 - inset - 0.5) }
        if (r[3] >= 2 * inset + 2) { inside += "left" to (x0 + inset to my); inside += "right" to (x1 - inset - 0.5 to my) }
        if (inside.isEmpty()) inside += "center" to (mx to my)
        val outside = listOf(
            "above" to (mx to y0 - 1.5), "above" to (mx to y0 - 3.5), "below" to (mx to y1 + 0.5), "below" to (mx to y1 + 2.5),
            "left of" to (x0 - 1.5 to my), "left of" to (x0 - 3.5 to my), "right of" to (x1 + 0.5 to my), "right of" to (x1 + 2.5 to my),
        )
        val ring = if (bw > 0) listOf("border top" to (mx to y0 + bw / 2), "border bottom" to (mx to y1 - bw / 2 - 0.5), "border left" to (x0 + bw / 2 to my), "border right" to (x1 - bw / 2 - 0.5 to my)) else emptyList()
        for ((where, p) in inside + ring + outside) {
            val x = p.first.toInt(); val y = p.second.toInt()
            if (x < 0 || y < 0 || x >= actual.w || y >= actual.h) continue
            val want = expected.at(x, y)
            val got = actual.nearest(x, y, want)
            if (dist(want, got) > tol.sample) d += Triple("${if (where in setOf("above", "below", "left of", "right of")) "outside, $where" else if (where.startsWith("border")) where else "fill, $where"} ($x,$y)", hex(want), hex(actual.at(x, y)))
        }
        row("box", id, label, d, r)
    }

    private fun checkIcon(el: JsonObject) {
        val r = rectOf(el)
        if (masked(r[0] + r[2] / 2, r[1] + r[3] / 2)) return
        if (el.isVariable()) return checkVariableIcon(el, r)
        val id = el.str("id")
        val x0 = max(0, r[0].toInt()); val y0 = max(0, r[1].toInt())
        val x1 = min(actual.w, (r[0] + r[2]).toInt() + 1); val y1 = min(actual.h, (r[1] + r[3]).toInt() + 1)
        var worst = 0
        var at = ""
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val dd = dist(cell(expected, x, y, x1, y1), cell(actual, x, y, x1, y1))
                if (dd > worst) { worst = dd; at = "$x,$y" }
                x += tol.cellDp
            }
            y += tol.cellDp
        }
        val d = mutableListOf<Triple<String, String, String>>()
        if (worst > tol.cell) d += Triple("pixels (worst ${tol.cellDp} dp cell at $at)", "frame", "differs by $worst of 255")
        // The colour the glyph is drawn in: the pixel of the rectangle furthest from its border colour, in the frame and in the screen.
        val rect = listOf(r[0], r[1], r[2], r[3])
        val want = farthest(expected, rect)
        val got = farthest(actual, rect)
        if (want != null && got != null && dist(want, got) > tol.iconColor) d += Triple("drawn colour", hex(want), hex(got))
        row("icon", id, "${el.str("node")} ${r[2].f()}x${r[3].f()} at ${r[0].f()},${r[1].f()}", d, r)
    }

    /** A logo, an avatar or a chart is drawn by the data: it must be there, at the frame's place and about its size, and what it draws is not compared. */
    private fun checkVariableIcon(el: JsonObject, r: List<Double>) {
        val seen = inkBox(expected, r)
        val shown = inkBox(actual, r)
        val d = mutableListOf<Triple<String, String, String>>()
        if (shown == null) d += Triple("picture", "a ${el.str("var")} at ${r[0].f()},${r[1].f()}", "missing")
        else if (seen != null) {
            fun cmp(field: String, want: Double, got: Double) { if (abs(want - got) > tol.textPosDp) d += Triple(field, want.f(), got.f()) }
            cmp("picture centre x", (seen[0] + seen[2]) / 2, (shown[0] + shown[2]) / 2)
            cmp("picture centre y", (seen[1] + seen[3]) / 2, (shown[1] + shown[3]) / 2)
            // The ink is read inside the frame's rectangle and 2 dp around it, so a picture that is too small is the one that can differ.
            if (shown[2] - shown[0] < (seen[2] - seen[0]) / 2) d += Triple("picture width", (seen[2] - seen[0]).f(), (shown[2] - shown[0]).f())
            if (shown[3] - shown[1] < (seen[3] - seen[1]) / 2) d += Triple("picture height", (seen[3] - seen[1]).f(), (shown[3] - shown[1]).f())
        }
        row("icon", el.str("id"), "${el.str("var")} ${el.str("node")} ${r[2].f()}x${r[3].f()} at ${r[0].f()},${r[1].f()}", d, r)
    }

    private fun farthest(img: Px, rect: List<Double>): Int? {
        val x0 = max(0, rect[0].toInt()); val y0 = max(0, rect[1].toInt())
        val x1 = min(img.w, (rect[0] + rect[2]).toInt() + 1); val y1 = min(img.h, (rect[1] + rect[3]).toInt() + 1)
        if (x1 <= x0 || y1 <= y0) return null
        val base = img.at(x0, y0)
        var best = base
        for (y in y0 until y1) for (x in x0 until x1) if (dist(img.at(x, y), base) > dist(best, base)) best = img.at(x, y)
        return best
    }

    private fun cell(img: Px, x: Int, y: Int, maxX: Int, maxY: Int): Int {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (yy in y until min(maxY, y + tol.cellDp)) for (xx in x until min(maxX, x + tol.cellDp)) {
            val c = img.at(xx, yy)
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
        }
        return if (n == 0) 0 else (r / n shl 16) or (g / n shl 8) or (b / n)
    }

    /**
     * Every [Tolerance.cellDp] dp cell whose average colour differs, joined into regions: what the element checks cannot name, such as a Text or a
     * picture the frame does not have. The pixels of a word or an icon of the frame are left out of the averages: their own checks measure them, and
     * a glyph drawn half a dp apart would move a cell's colour far more than the tolerance. A word is left out with [Tolerance.textPosDp] around it
     * (its checks allow that much), an icon with 1 dp, and a variable Text with the room it has to grow into, up to the next word or icon of its
     * line. A box is left out of nothing. A cell with fewer than 4 pixels left is not compared.
     */
    private fun checkRegions(elements: List<JsonObject>) {
        val k = tol.cellDp
        val cw = (actual.w + k - 1) / k
        val ch = (actual.h + k - 1) / k
        val skip = BooleanArray(actual.w * actual.h)
        val placed = elements.filter { it.str("kind") != "box" }
        for (el in placed) {
            val r = rectOf(el)
            var x0 = r[0]; var x1 = r[0] + r[2]
            val pad = if (el.str("kind") == "text") tol.textPosDp else 1.0
            if (el.isVariable() && el.str("kind") == "text") {
                val anchor = el.str("anchor")
                val line = placed.filter { it !== el && rectOf(it).let { o -> min(o[1] + o[3], r[1] + r[3]) - max(o[1], r[1]) > 1 } }
                if (anchor != "left") x0 = line.map { rectOf(it) }.filter { it[0] + it[2] <= r[0] + 0.5 }.maxOfOrNull { it[0] + it[2] } ?: 0.0
                if (anchor != "right") x1 = line.map { rectOf(it) }.filter { it[0] >= r[0] + r[2] - 0.5 }.minOfOrNull { it[0] } ?: actual.w.toDouble()
            }
            for (y in max(0, floor(r[1] - pad).toInt()) until min(actual.h, ceil(r[1] + r[3] + pad).toInt()))
                for (x in max(0, floor(min(x0, r[0]) - pad).toInt()) until min(actual.w, ceil(max(x1, r[0] + r[2]) + pad).toInt())) skip[y * actual.w + x] = true
        }
        fun average(img: Px, cx: Int, cy: Int): Triple<Int, Int, Int>? {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (y in cy * k until min(actual.h, cy * k + k)) for (x in cx * k until min(actual.w, cx * k + k)) {
                if (skip[y * actual.w + x]) continue
                val c = img.at(x, y)
                r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
            }
            return if (n < 4) null else Triple(r / n, g / n, b / n)
        }
        val bad = Array(ch) { cy ->
            IntArray(cw) { cx ->
                val e = average(expected, cx, cy)
                val a = average(actual, cx, cy)
                if (e == null || a == null) 0 else max(abs(e.first - a.first), max(abs(e.second - a.second), abs(e.third - a.third)))
            }
        }
        val seen = Array(ch) { BooleanArray(cw) }
        var n = 0
        for (cy in 0 until ch) for (cx in 0 until cw) {
            if (seen[cy][cx] || bad[cy][cx] <= tol.cell) continue
            var minX = cx; var maxX = cx; var minY = cy; var maxY = cy; var worst = 0
            val stack = ArrayDeque(listOf(cx to cy))
            seen[cy][cx] = true
            while (stack.isNotEmpty()) {
                val (px, py) = stack.removeLast()
                minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py); worst = max(worst, bad[py][px])
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx; val ny = py + dy
                    if (nx in 0 until cw && ny in 0 until ch && !seen[ny][nx] && bad[ny][nx] > tol.cell) { seen[ny][nx] = true; stack.addLast(nx to ny) }
                }
            }
            val rect = listOf(minX * k.toDouble(), minY * k.toDouble(), (maxX - minX + 1) * k.toDouble(), (maxY - minY + 1) * k.toDouble())
            row("region", "region${n++}", "region ${rect[2].f()}x${rect[3].f()} at ${rect[0].f()},${rect[1].f()}", listOf(Triple("average colour", "frame", "differs by up to $worst of 255")), rect)
        }
    }
}
