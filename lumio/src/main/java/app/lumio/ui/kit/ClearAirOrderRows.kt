package app.lumio.ui.kit

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/** The index of no row, when no row is dragged. */
private const val NO_ROW = -1

/** The height of a row with one line of text, as the rows of an ordered list are drawn. */
private val ROW_HEIGHT = 62.dp

/** The width of the number at the start of an ordered row. */
private val NUMBER_WIDTH = 24.dp

/**
 * A column of rows the owner reorders by dragging a row's handle. [row] draws each row with its [index], its [item] and its [handle], the modifier that
 * starts a drag. A dragged row follows the finger. On release it takes the place its centre reaches, and [onReorder] gets the keys of the rows in their
 * new order, as [key] gives them. The rows keep their places until then.
 */
@Composable
fun <T> ClearAirDraggableRows(
    rows: List<T>,
    key: (T) -> String,
    onReorder: (List<String>) -> Unit,
    row: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    val currentRows by rememberUpdatedState(rows)
    val currentKey by rememberUpdatedState(key)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val centres = remember { HashMap<Int, Float>() }
    var dragged by remember { mutableIntStateOf(NO_ROW) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, item ->
            val lifted = dragged == index
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        centres[index] = coordinates.positionInParent().y + coordinates.size.height / 2f
                    }
                    .zIndex(if (lifted) 1f else 0f)
                    .offset { IntOffset(0, if (lifted) dragOffset.roundToInt() else 0) },
            ) {
                row(
                    index,
                    item,
                    Modifier.pointerInput(index) {
                        detectDragGestures(
                            onDragStart = {
                                dragged = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val from = dragged
                                val measured = currentRows.indices.map { centres[it] }
                                if (from != NO_ROW && measured.all { it != null }) {
                                    val to = dropIndex(measured.map { it ?: 0f }, from, dragOffset)
                                    if (to != from) {
                                        val keys = currentRows.map(currentKey).toMutableList()
                                        keys.add(to, keys.removeAt(from))
                                        currentOnReorder(keys)
                                    }
                                }
                                dragged = NO_ROW
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                dragged = NO_ROW
                                dragOffset = 0f
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * The index of the row a drag lands on: among [centres], the one nearest to where row [from] has been moved, which is its own centre plus [offset].
 * [centres] holds the vertical centre of each row, top to bottom.
 */
internal fun dropIndex(centres: List<Float>, from: Int, offset: Float): Int {
    val target = centres[from] + offset
    return centres.indices.minBy { abs(centres[it] - target) }
}

/**
 * A row of an ordered list: its [number], then a [title] with a [description] under it. A row with a [dragHandle] shows the handle at its end. A row
 * without one shows a chevron when [onClick] opens it. The text opens the row, and the handle starts a drag, so the two do not meet.
 */
@Composable
fun ClearAirNumberedRow(
    number: Int,
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    dragHandle: Modifier? = null,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = number.toString(), modifier = Modifier.width(NUMBER_WIDTH), style = ClearAirType.body, color = palette.ink3)
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null) {
                        Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink)
            if (description != null) {
                Text(text = description, style = ClearAirType.body, color = palette.ink2)
            }
        }
        if (dragHandle != null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Box(modifier = dragHandle.size(ClearAirSpacing.touchTarget), contentAlignment = Alignment.Center) {
                ClearAirIcon(glyph = ClearAirGlyph.DRAG, color = palette.ink3)
            }
        } else if (onClick != null) {
            Spacer(Modifier.width(ClearAirSpacing.xxs))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3)
        }
    }
}
