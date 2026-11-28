package app.lumio.ui.kit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

/** An empty space of [height] in a [RowStack]. */
@Composable
internal fun Gap(height: Dp) = Spacer(Modifier.designedHeight(height, exact = true))

/**
 * A column that lays its children out by their designed heights (#1742). A row of 62 dp is 108.5 px at 1.75 px a dp, and a column that draws each as 109 px
 * lets the half pixel add up row after row, as it does with every other half-pixel gap. Here a child that has a [designedHeight] ends where the running total of
 * designed heights does, rounded once, so each is 108 or 109 px and none sits more than half a pixel from its design. A child with no designed height, or a
 * row that its content has made taller, keeps its own height. At a whole number of pixels per dp this is a plain column.
 */
@Composable
internal fun RowStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        var designed = 0f
        val rows = measurables.map { measurable ->
            val declared = measurable.parentData as? DesignedHeight
            val height = declared?.height?.toPx()
            val top = designed.roundToInt()
            val snapped = height?.let { (designed + it).roundToInt() - top }
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
        layout(rows.maxOfOrNull { it.second.width } ?: constraints.minWidth, designed.roundToInt()) {
            rows.forEach { (top, placeable) -> placeable.place(0, top) }
        }
    }
}
