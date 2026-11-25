package app.lumio.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon

/** The glyphs of the settings pages that the kit's set has not got yet, with the path data of the approved mock. Each is drawn on a 24 unit grid. */
internal enum class SettingsGlyph(private val data: List<String>) {
    KEY(listOf("M4.5 15.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0", "M10.5 13L19 4.5M16 7.5l2.5 2.5M13.5 10l1.8 1.8")),
    FINGERPRINT(
        listOf(
            "M7.4 18.6c-.8-1.9-1.2-3.9-1.2-6.1a5.8 5.8 0 0 1 9.7-4.3M17.6 10.3c.1.7.2 1.4.2 2.2 0 1.6-.2 3.2-.6 4.6" +
                "M9.7 20.1c-.7-1.7-1-3.5-1-5.4v-2.2a3.3 3.3 0 0 1 6.6 0v1.9M12 12.5v2.2c0 2 .4 3.9 1.1 5.6",
        ),
    ),
    HAND(listOf("M9 12V6.5a1.5 1.5 0 0 1 3 0V11M12 10.5V9a1.5 1.5 0 0 1 3 0v2M15 10.5a1.5 1.5 0 0 1 3 0V14c0 3.6-2.6 6.5-6.2 6.5-2.3 0-3.8-1-5-2.8l-2.2-3.3a1.5 1.5 0 0 1 2.5-1.6L9 15")),
    CLOCK(listOf("M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0", "M12 7.5V12l3 2")),
    DOC(listOf("M7 3.5h7l4.5 4.5v12.5H7zM14 3.5V8h4.5M9.5 12.5h6M9.5 16h6")),
    BOOK(listOf("M5 18.5V6a2 2 0 0 1 2-2h12v13H7a2 2 0 0 0-2 2 2 2 0 0 0 2 2h12")),
    REFRESH(listOf("M19.5 12a7.5 7.5 0 1 1-2.3-5.4M19.5 4.5V9H15")),
    TRASH(listOf("M4.5 7h15M9.5 7V4.8h5V7M6.5 7l1 13h9l1-13M10 11v5.5M14 11v5.5")),
    ;

    private val paths by lazy { data.map { PathParser.createPathFromPathData(it).asComposePath() } }

    /** Draws the glyph in [color] to fill the size of the canvas. */
    internal fun draw(scope: DrawScope, color: Color) = with(scope) {
        val unit = size.width / 24f
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            paths.forEach { drawPath(it, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

/** The leading mark of a settings row: a [glyph] in a raised disc. A [destructive] row has the glyph in rose. */
@Composable
internal fun SettingsDisc(glyph: SettingsGlyph, destructive: Boolean = false) {
    val palette = LocalClearAirPalette.current
    val color = if (destructive) palette.rose else palette.ink
    Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(palette.raised), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(19.dp)) { glyph.draw(this, color) }
    }
}

/** The corner radius of a note, the mock's 14 dp. */
private val NOTE_CORNER = 14.dp

/** A note of the settings pages: one line of fact on the raised layer, with an info mark, in the page gutter. */
@Composable
internal fun SettingsInfoNote(text: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .padding(horizontal = ClearAirSpacing.gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(NOTE_CORNER))
            .background(palette.raised)
            .padding(horizontal = 14.dp, vertical = ClearAirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.INFO, color = palette.ink2)
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(text = text, modifier = Modifier.weight(1f), style = ClearAirType.label.copy(fontWeight = FontWeight.Normal), color = palette.ink)
    }
}

/** A line of facts under a title: its [label] in ink2 at the start, its [value] in bold ink at the end, and a hairline under it unless it is [last]. */
@Composable
internal fun SettingsFact(label: String, value: String, last: Boolean = false) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = ClearAirType.body, color = palette.ink2)
            Text(text = value, style = ClearAirType.rowTitle, color = palette.ink, textAlign = TextAlign.End)
        }
        if (!last) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
    }
}

/** A text action led by a plus: the page's one way to add something, in bold ink. */
@Composable
internal fun SettingsAddAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.PLUS, color = palette.ink)
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(text = text, style = ClearAirType.rowTitle, color = palette.ink, maxLines = 1)
    }
}
