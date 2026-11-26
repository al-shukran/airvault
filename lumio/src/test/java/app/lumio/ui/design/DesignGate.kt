package app.lumio.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

private class Mask(row: JsonObject) {
    val rect = row.getValue("rect").jsonArray.map { it.jsonPrimitive.double() }
    fun contains(x: Double, y: Double) = x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3]
}

private class Tolerance(row: JsonObject) {
    val bandDp = row.num("bandDp")
    val textPosDp = row.num("textPosDp")
    val textWidthDp = row.num("textWidthDp")
    val fontSizeDp = row.num("fontSizeDp")
    val lineHeightDp = row.num("lineHeightDp")
    val letterSpacingDp = row.num("letterSpacingDp")
    val textColor = row.num("textColor").toInt()
    val sample = row.num("sample").toInt()
    val cell = row.num("cell").toInt()
    val cellDp = row.num("cellDp").toInt()
}

/** The app's text, read from the semantics tree: what each Text says and where, and the style it was laid out with. Positions are in the frame's dp. */
private class AppText(val text: String, val x: Double, val y: Double, val w: Double, val h: Double, val size: Double, val weight: Int, val lineHeight: Double?, val letterSpacing: Double, val color: Int?, val colorAlpha: Double) {
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
                val lines = layout.lineCount
                val left = (0 until lines).minOf { layout.getLineLeft(it) }
                val right = (0 until lines).maxOf { layout.getLineRight(it) }
                val top = layout.getLineTop(0)
                val bottom = layout.getLineBottom(lines - 1)
                val spacing = when {
                    style.letterSpacing.isEm -> style.letterSpacing.value * size
                    style.letterSpacing.isSp -> with(density) { style.letterSpacing.toPx().toDouble() }
                    else -> 0.0
                }
                out += AppText(
                    text = layout.layoutInput.text.text,
                    x = node.positionInRoot.x - originX + left.toDouble(),
                    y = node.positionInRoot.y - originY + top.toDouble(),
                    w = (right - left).toDouble(),
                    h = (bottom - top).toDouble(),
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
private class Comparison(
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
        elements.filter { it.str("kind") == "text" }.forEach { checkText(it) }
        texts.filter { !it.used && it.x < actual.w && it.y < actual.h && it.x + it.w > 0 && it.y + it.h > 0 && !masked(it.cx, it.cy) }.forEach {
            row("text", "extra", "'${it.text}'", listOf(Triple("text", "absent from the frame", "at ${it.x.f()},${it.y.f()}")), listOf(it.x, it.y, it.w, it.h))
        }
        elements.filter { it.str("kind") == "box" }.forEach { checkBox(it) }
        elements.filter { it.str("kind") == "icon" }.forEach { checkIcon(it) }
        checkRegions()
        return Result(name, findings, rows, bandsWant to bandsGot)
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
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
        fun cmp(field: String, want: Double, got: Double, t: Double) { if (abs(want - got) > t) d += Triple(field, want.f(), got.f()) }
        cmp("left x", r[0], match.x, tol.textPosDp)
        cmp("center y", cy, match.cy, tol.textPosDp)
        cmp("width", r[2], match.w, tol.textWidthDp)
        val font = el.getValue("font").jsonObject
        cmp("font size", font.num("size"), match.size, tol.fontSizeDp)
        if (font.num("weight").toInt() != match.weight) d += Triple("font weight", "${font.num("weight").toInt()}", "${match.weight}")
        font["lineHeight"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.double()?.let { want ->
            if (match.lineHeight == null) d += Triple("line height", want.f(), "unset") else cmp("line height", want, match.lineHeight, tol.lineHeightDp)
        }
        cmp("letter spacing", font.num("letterSpacing"), match.letterSpacing, tol.letterSpacingDp)
        match.color?.let { got ->
            val want = el.getValue("color").jsonArray.map { it.jsonPrimitive.double() }.rgb(bg)
            val shown = ((match.colorAlpha * 255).roundToInt()).let { a -> if (a == 255) got else listOf((got shr 16 and 255).toDouble(), (got shr 8 and 255).toDouble(), (got and 255).toDouble(), match.colorAlpha).rgb(bg) }
            if (dist(want, shown) > tol.textColor) d += Triple("color", hex(want), hex(shown))
        }
        row("text", id, label, d, r)
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
        val outside = listOf("above" to (mx to y0 - 2.5), "below" to (mx to y1 + 1.5), "left of" to (x0 - 2.5 to my), "right of" to (x1 + 1.5 to my))
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
        val d = if (worst > tol.cell) listOf(Triple("pixels (worst ${tol.cellDp} dp cell at $at)", "frame", "differs by $worst of 255")) else emptyList()
        row("icon", id, "${el.str("node")} ${r[2].f()}x${r[3].f()} at ${r[0].f()},${r[1].f()}", d, r)
    }

    private fun cell(img: Px, x: Int, y: Int, maxX: Int, maxY: Int): Int {
        var r = 0; var g = 0; var b = 0; var n = 0
        for (yy in y until min(maxY, y + tol.cellDp)) for (xx in x until min(maxX, x + tol.cellDp)) {
            val c = img.at(xx, yy)
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++
        }
        return if (n == 0) 0 else (r / n shl 16) or (g / n shl 8) or (b / n)
    }

    /** Every [Tolerance.cellDp] dp cell of the frame whose average colour differs, joined into regions: what the element checks cannot name (a chart, a missing element). */
    private fun checkRegions() {
        val k = tol.cellDp
        val cw = (actual.w + k - 1) / k
        val ch = (actual.h + k - 1) / k
        val bad = Array(ch) { cy -> IntArray(cw) { cx -> dist(cell(expected, cx * k, cy * k, actual.w, actual.h), cell(actual, cx * k, cy * k, actual.w, actual.h)) } }
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
