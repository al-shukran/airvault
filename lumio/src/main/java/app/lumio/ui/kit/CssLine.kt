package app.lumio.ui.kit

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp

/**
 * Gives a line of text at least the height [height] and centres the text in it, as a CSS line box does. Compose trims the half-leading above and below a
 * line, so the text's own box is shorter than its line height and a mock's spacing between lines would sit off. A text that wraps keeps its own height.
 */
internal fun Modifier.cssLine(height: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val box = maxOf(height.roundToPx(), placeable.height)
    layout(placeable.width, box) { placeable.placeRelative(0, (box - placeable.height) / 2) }
}

/**
 * The line box of a mock's browser: the line height is the box's height, and the font's own height sits in it with the same leading above and below,
 * which is how CSS sets a line. Compose's default puts the leading in proportion to the font's ascent and descent, which sets a small face a
 * dp or so lower than the mock does.
 */
internal fun TextStyle.inLineBox(): TextStyle = copy(
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
)

/**
 * Sets a text's width as a browser does: its letter spacing, [spacing], is also taken after the last letter, which Compose leaves out. Without it a
 * run of texts set side by side comes out wider or narrower than the mock's by the spacing of each run.
 */
internal fun Modifier.spacedLikeCss(spacing: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width + spacing.roundToPx(), placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Centres a block in a row as a browser's flex row does: a half dp left over goes below the block. Compose's own centring rounds it up, so a two-line
 * text column in a 62 dp row beside a 36 dp disc sits 1 dp under the mock's, where the mock's column starts half a dp above the disc.
 */
internal val CssCentre = Alignment.Vertical { size, space -> (space - size) / 2 }
