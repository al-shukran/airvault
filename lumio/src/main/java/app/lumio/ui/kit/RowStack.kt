package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What a child tells its [RowStack]: the height it is designed at, and whether it is exactly that or that at least. */
private class DesignedHeight(val height: Dp, val exact: Boolean)

/**
 * Tells the [RowStack] that holds this child that it is designed [height] tall, so the stack can place it by that height and not by its rounded pixels. A child
 * that is [exact] is that tall whatever its content; any other is that tall at least, and is as tall as its content when the content is taller. Anywhere
 * else the modifier does nothing.
 */
internal fun Modifier.designedHeight(height: Dp, exact: Boolean = false): Modifier = this.then(object : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = DesignedHeight(height, exact)
})

/** The part of a pixel that a height may be short of a whole pixel and still count as it, for the rounding of a density that is not a power of two. */
private const val ROUNDING_SLACK = 0.001f

/** An empty space of [height] in a [RowStack]. */
@Composable
internal fun Gap(height: Dp) = Spacer(Modifier.designedHeight(height, exact = true))

/** [content] set at the top left of a box that is exactly [height] tall in a [RowStack], for a block whose own height is not that exact. */
@Composable
internal fun Designed(height: Dp, content: @Composable () -> Unit) = Box(Modifier.fillMaxWidth().designedHeight(height, exact = true)) { content() }

/**
 * A column that lays its children out by their designed heights (#1742). A row of 62 dp is 108.5 px at 1.75 px a dp, and a column that draws each as 109 px
 * lets the half pixel add up row after row, as it does with every other half-pixel gap. Here a child that has a [designedHeight] starts where the running total of
 * designed heights does, rounded once, so none sits more than half a pixel from its design. A child with no designed height, or a row that its content has made
 * taller, keeps its own height. At a whole number of pixels per dp this is a plain column.
 */
@Composable
internal fun RowStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        var designed = 0f
        val rows = measurables.map { measurable ->
            val declared = measurable.parentData as? DesignedHeight
            val height = declared?.height?.toPx()
            val top = designed.roundToInt()
            // An exact child fills the space between its top and the next, so the stack ends where its design does. Any other is the same pixels tall wherever it
            // sits, the design rounded up, so what a row centres in itself sits the same in each row, and the row is not 108 px tall here and 109 there.
            val snapped = height?.let { if (declared?.exact == true) (designed + it).roundToInt() - top else ceil(it - ROUNDING_SLACK).toInt() }
            val placeable = measurable.measure(
                when {
                    declared == null || snapped == null -> loose
                    declared.exact -> loose.copy(minHeight = snapped, maxHeight = snapped)
                    else -> loose.copy(minHeight = snapped)
                },
            )
            designed += if (height != null && placeable.height == snapped) height else placeable.height.toFloat()
            top to placeable
        }
        // A size under the constraints' minimum would be centred in it, so the content is sized to the minimum and sits at the top, as a column's does.
        val width = (rows.maxOfOrNull { it.second.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, designed.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)) {
            rows.forEach { (top, placeable) -> placeable.place(0, top) }
        }
    }
}
