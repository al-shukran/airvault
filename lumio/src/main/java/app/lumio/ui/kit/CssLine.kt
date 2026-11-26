package app.lumio.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Gives a line of text at least the height [height] and centres the text in it, as a CSS line box does. Compose trims the half-leading above and below a
 * line, so the text's own box is shorter than its line height and a mock's spacing between lines would sit off. A text that wraps keeps its own height.
 * A [lift] raises the text, for the small faces that Compose sets lower in their line than the mock's browser does.
 */
internal fun Modifier.cssLine(height: Dp, lift: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val box = maxOf(height.roundToPx(), placeable.height)
    layout(placeable.width, box) { placeable.placeRelative(0, (box - placeable.height) / 2 - lift.roundToPx()) }
}
