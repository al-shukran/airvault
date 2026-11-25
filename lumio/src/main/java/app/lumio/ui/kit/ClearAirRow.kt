package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a row, from the proposal. A row whose text wraps grows with it. */
private val ROW_HEIGHT = 62.dp

/** The height of a [compact] row, which the settings pages use. */
private val COMPACT_HEIGHT = 56.dp

/** The side of a leading disc: a logo, a monogram or a glyph. A divider starts after it, where the row's text does. */
private val DISC = 36.dp

/** The description's size: one step under [ClearAirType.body], as a row's second line is drawn. */
private val DESCRIPTION = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)

/** The gap between a description and its suffix: the 6 dp the suffix is set off by, less the 4 dp its first logo is pulled back by. */
private val SUFFIX_GAP = 2.dp

/** The index after a title, such as the "#5" of an account, in the mono face. */
private val INDEX = ClearAirType.address.copy(fontSize = 11.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)

/**
 * A row of a list: an optional [leading] logo, monogram or glyph disc, a [title], a [description] under it when given, an optional [trailing] value, and a
 * chevron when the row opens something ([onClick]). The row is 62 dp at least and grows with its text, which wraps and is never cut. A [descriptionSuffix]
 * sits after the description's text on its line, such as a row of chain logos. A [styledDescription] takes the place of the [description] when its words
 * carry their own colours, such as a status word. A [tag] and an [index] follow the title. A [divider] is a hairline on the
 * row's top edge, which starts at the text when the row has a [leading] mark and at the gutter when it has none, so a list sets it on every row but the
 * first. The row is full-bleed with the page gutter on each side, and it scales to 0.98 while pressed. A [destructive] row has its title in rose.
 */
@Composable
fun ClearAirRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    styledDescription: AnnotatedString? = null,
    descriptionSuffix: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    tag: String? = null,
    index: String? = null,
    divider: Boolean = false,
    compact: Boolean = false,
    chevron: Boolean = onClick != null,
) {
    val palette = LocalClearAirPalette.current
    val inset = if (leading != null) ClearAirSpacing.gutter + DISC + ClearAirSpacing.sm else ClearAirSpacing.gutter
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    drawLine(
                        color = palette.hairline,
                        start = Offset(inset.toPx(), 0f),
                        end = Offset(size.width - ClearAirSpacing.gutter.toPx(), 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .defaultMinSize(minHeight = if (compact) COMPACT_HEIGHT else ROW_HEIGHT)
            .then(
                if (onClick != null) {
                    Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = ClearAirType.rowTitle,
                    color = if (destructive) palette.rose else palette.ink,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (index != null) {
                    Text(text = index, style = INDEX, color = palette.ink2, maxLines = 1, modifier = Modifier.padding(start = ClearAirSpacing.xxs))
                }
                if (tag != null) {
                    ClearAirTag(text = tag, modifier = Modifier.padding(start = 6.dp))
                }
            }
            val line = styledDescription ?: description?.let(::AnnotatedString)
            if (line != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line,
                        style = DESCRIPTION,
                        color = palette.ink2,
                        modifier = if (descriptionSuffix == null) Modifier else Modifier.weight(1f, fill = false),
                    )
                    if (descriptionSuffix != null) {
                        Spacer(Modifier.width(SUFFIX_GAP))
                        descriptionSuffix()
                    }
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
            ClearAirIcon(
                glyph = ClearAirGlyph.CHEVRON,
                color = palette.ink3,
                size = 16.dp,
                modifier = Modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f },
            )
        }
    }
}

/** A logo, monogram or glyph on the 36 dp raised disc that leads a row. */
@Composable
fun ClearAirDisc(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.size(DISC).clip(CircleShape).background(LocalClearAirPalette.current.raised),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A [glyph] in ink on a leading disc: a settings row's mark, drawn at 19 dp, or a list row's at 21. */
@Composable
fun ClearAirGlyphDisc(glyph: ClearAirGlyph, modifier: Modifier = Modifier, color: Color = LocalClearAirPalette.current.ink, size: Dp = 21.dp) {
    ClearAirDisc(modifier) { ClearAirIcon(glyph = glyph, color = color, size = size) }
}

/** The value at the end of a row: [value] in the mono face over an optional [note] in the secondary colour, both right-aligned. */
@Composable
fun ClearAirRowValue(value: String, note: String? = null, noteColor: Color = LocalClearAirPalette.current.ink2) {
    Column(horizontalAlignment = Alignment.End) {
        Text(text = value, style = ClearAirType.rowValue, color = LocalClearAirPalette.current.ink, textAlign = TextAlign.End)
        if (note != null) {
            Text(text = note, style = ClearAirType.time.copy(fontWeight = FontWeight.Normal), color = noteColor, textAlign = TextAlign.End)
        }
    }
}

