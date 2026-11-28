package app.lumio.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

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
    layout((placeable.width + spacing.roundToPx()).coerceAtLeast(0), placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Centres a block in a row as a browser's flex row does: a half dp left over goes below the block. Compose's own centring rounds it up, so a two-line
 * text column in a 62 dp row beside a 36 dp disc sits 1 dp under the mock's, where the mock's column starts half a dp above the disc.
 */
internal val CssCentre = Alignment.Vertical { size, space -> (space - size) / 2 }

/** The height a child of [FrameColumn] takes in the frame. */
private class FrameHeight(val height: Dp)

/**
 * Gives a child of [FrameColumn] its height in the frame, [height], which the column steps down by in place of the whole pixels the child rounds to.
 * A child more than a pixel taller, as text is at a large font size, steps the column down by its own height.
 */
internal fun Modifier.frameHeight(height: Dp): Modifier = layoutId(FrameHeight(height))

/**
 * Stacks its children top to bottom as a browser lays out a frame: each child's top is the sum of the frame heights above it, rounded to a pixel once.
 * A Column rounds each height on its own, so at a density such as 1.75, where 2 dp is 3.5 px, the half pixels add up and the bottom of a page sits
 * several dp under the frame's. A child narrower than the column is centred.
 */
@Composable
internal fun FrameColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        var y = 0f
        val tops = placeables.mapIndexed { i, placeable ->
            val top = y.roundToInt()
            val frame = (measurables[i].layoutId as? FrameHeight)?.height?.toPx()
            y += if (frame != null && placeable.height <= frame + GROWN_PX) frame else placeable.height.toFloat()
            top
        }
        layout(width, y.roundToInt()) { placeables.forEachIndexed { i, placeable -> placeable.placeRelative((width - placeable.width) / 2, tops[i]) } }
    }
}

/** How much taller than its frame height a child may round to before it counts as grown. */
private const val GROWN_PX = 1.5f
