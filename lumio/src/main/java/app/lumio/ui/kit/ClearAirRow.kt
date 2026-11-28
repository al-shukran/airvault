package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
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
internal val ROW_DESCRIPTION = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)

/** The gap between a description and its suffix: the 6 dp the suffix is set off by, less the 4 dp its first logo is pulled back by. */
private val SUFFIX_GAP = 2.dp

/** A [lineBox] row's description sits 1 dp lower than its line box puts it, as the approved Explore frame has it. */
private val LINE_BOX_DESCRIPTION_NUDGE = 1.dp

/** The index after a title, such as the "#5" of an account, in the mono face. */
private val INDEX = ClearAirType.address.copy(fontSize = 11.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)

/**
 * A row of a list: an optional [leading] logo, monogram or glyph disc, a [title], a [description] under it when given, an optional [trailing] value, and a
 * chevron when the row opens something ([onClick]). The row is 62 dp at least and grows with its text, which wraps and is never cut. A [descriptionSuffix]
 * sits after the description's text on its line, such as a row of chain logos. A [descriptionLine] takes the place of the [description] when its parts are
 * separate elements, such as a status glyph, its word and a note. A [tag] and an [index] follow the title. A [divider] is a hairline on the
 * row's top edge, which starts at the text when the row has a [leading] mark and at the gutter when it has none, so a list sets it on every row but the
 * first. A [lineBox] row sets its title and description in the line box of the mock's browser, centred as its flex row centres them, as the approved
 * Explore frame has them. An [address] is the row's last line, in the address style: under the header band, from the text's left edge to the gutter, when the
 * row has a description, and in the description's place when it has none. The row is full-bleed with the page gutter on each side, and it scales to 0.98 while pressed. A [destructive] row has its title in rose.
 */
@Composable
fun ClearAirRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionLine: (@Composable RowScope.() -> Unit)? = null,
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
    lineBox: Boolean = false,
    address: String? = null,
) {
    val palette = LocalClearAirPalette.current
    val titleStyle = if (lineBox) ClearAirType.rowTitle.inLineBox() else ClearAirType.rowTitle
    val descriptionStyle = if (lineBox) ROW_DESCRIPTION.inLineBox() else ROW_DESCRIPTION
    val inset = if (leading != null) ClearAirSpacing.gutter + DISC + ClearAirSpacing.sm else ClearAirSpacing.gutter
    val footer = address != null && (description != null || descriptionLine != null)
    val minHeight = if (compact) COMPACT_HEIGHT else ROW_HEIGHT
    val divided = Modifier.drawBehind {
        if (divider) {
            drawLine(
                color = palette.hairline,
                start = Offset(inset.toPx(), 0f),
                end = Offset(size.width - ClearAirSpacing.gutter.toPx(), 0f),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
    val pressed = if (onClick != null) {
        Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
    } else {
        Modifier
    }
    val content: @Composable RowScope.() -> Unit = {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f).then(if (lineBox) Modifier.align(CssCentre) else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = titleStyle,
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
            val descriptionModifier = if (lineBox) Modifier.offset(y = LINE_BOX_DESCRIPTION_NUDGE) else Modifier
            if (descriptionLine != null) {
                Row(modifier = descriptionModifier, verticalAlignment = Alignment.CenterVertically, content = descriptionLine)
            } else if (description != null) {
                Row(modifier = descriptionModifier, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = description,
                        style = descriptionStyle,
                        color = palette.ink2,
                        modifier = if (descriptionSuffix == null) Modifier else Modifier.weight(1f, fill = false),
                    )
                    if (descriptionSuffix != null) {
                        Spacer(Modifier.width(SUFFIX_GAP))
                        descriptionSuffix()
                    }
                }
            } else if (address != null) {
                AddressLine(address = address, modifier = Modifier.padding(top = ADDRESS_GAP))
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
    AddressRow(
        minHeight = minHeight,
        addressInset = inset - ClearAirSpacing.gutter,
        footerAddress = address.takeIf { footer },
        modifier = modifier.designedHeight(minHeight).then(divided),
        press = pressed,
        content = content,
    )
}

/** A logo, monogram or glyph on the raised disc that leads a row, 36 dp unless a page head sets its own [size]. */
@Composable
fun ClearAirDisc(modifier: Modifier = Modifier, size: Dp = DISC, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(LocalClearAirPalette.current.raised),
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

