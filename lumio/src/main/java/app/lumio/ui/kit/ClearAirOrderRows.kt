package app.lumio.ui.kit

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lumio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/** The index of no row, when no row is dragged. */
private const val NO_ROW = -1

/** The height of a row with one line of text, as the rows of an ordered list are drawn. */
private val ROW_HEIGHT = 62.dp

/** The width of the number at the start of an ordered row. */
private val NUMBER_WIDTH = 24.dp

/** A lifted row grows by 2 percent and stands 8 dp above the page, on a card that is the colour of a sheet. */
private const val LIFT_SCALE = 1.02f
private val LIFT_ELEVATION = 8.dp
private val LIFT_SHAPE = RoundedCornerShape(12.dp)

/** The page scrolls while the finger is within this distance of its top or bottom, and faster the closer the finger is. */
private val SCROLL_ZONE = 72.dp
private val SCROLL_SPEED = 720.dp

/** How long a row waits for the new order to arrive after it has settled, before it goes back to where it was. */
private const val ORDER_WAIT_MS = 400L

/**
 * The page that scrolls the rows, so that a drag can scroll it. [state] is the page's scroll, and [viewport] is the page's area in the window, which the
 * page reports as it is laid out.
 */
class PageScroll(val state: ScrollState) {
    var viewport: Rect = Rect.Zero
}

/** The scrolling page that the rows sit in, or null when the rows do not scroll. */
val LocalPageScroll = staticCompositionLocalOf<PageScroll?> { null }

/**
 * A column of rows the owner reorders. Pressing a row's handle, or pressing and holding anywhere on the row, lifts it. The lifted row follows the finger,
 * the rows it passes slide out of its way, and the page scrolls when the finger nears its top or bottom. On release the row settles into its place and
 * [onReorder] gets the keys of the rows in their new order, as [key] gives them. [row] draws each row with its [index], its [item] and its [handle], the
 * modifier that makes the handle lift the row. TalkBack gets Move Up and Move Down on each row.
 */
@Composable
fun <T> ClearAirDraggableRows(
    rows: List<T>,
    key: (T) -> String,
    onReorder: (List<String>) -> Unit,
    row: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberLumioHaptics()
    val page = LocalPageScroll.current
    val reduced = LocalReducedMotion.current
    val currentKeys by rememberUpdatedState(rows.map(key))
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentPage by rememberUpdatedState(page)
    val currentReduced by rememberUpdatedState(reduced)
    val state = remember { ReorderState(scope, haptics, { currentKeys }, { currentOnReorder(it) }, { currentPage }, { currentReduced }) }
    val keys = currentKeys
    state.ensureRows(rows.size)
    val active = state.from != NO_ROW && keys == state.startOrder
    val hover by state.hover
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val palette = LocalClearAirPalette.current
    val moveUp = stringResource(R.string.reorder_move_up)
    val moveDown = stringResource(R.string.reorder_move_down)

    LaunchedEffect(active) { if (!active && state.from != NO_ROW) state.clear() }
    LaunchedEffect(state) {
        var last = NO_ROW
        snapshotFlow { state.hover.value }.collect { slot ->
            if (slot != NO_ROW && last != NO_ROW && slot != last) haptics.tick()
            last = slot
        }
    }
    val dragging = active && !state.settling
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val zone = with(density) { SCROLL_ZONE.toPx() }
        val speed = with(density) { SCROLL_SPEED.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            state.autoScroll((now - last) / 1_000_000_000f, zone, speed)
            last = now
        }
    }

    Column(modifier = Modifier.fillMaxWidth().onGloballyPositioned { state.root = it }) {
        rows.forEachIndexed { index, item ->
            key(keys[index]) {
                val dragged = active && state.from == index
                val shift = animateFloatAsState(
                    targetValue = if (active) shiftOf(index, state.from, hover, state.heights) else 0f,
                    animationSpec = if (active && !reduced) ClearAirMotion.reorder else snap(),
                    label = "reorder-shift",
                )
                val lift = animateFloatAsState(
                    targetValue = if (dragged && !state.settling) 1f else 0f,
                    animationSpec = if (reduced) snap() else ClearAirMotion.press,
                    label = "reorder-lift",
                )
                val actions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { currentOnReorder(keys.moved(index, index - 1)); true })
                    if (index < keys.lastIndex) add(CustomAccessibilityAction(moveDown) { currentOnReorder(keys.moved(index, index + 1)); true })
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { state.place(index, it.positionInParent().y, it.size.height.toFloat()) }
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) state.offset else if (active) shift.value else 0f
                            val raised = lift.value
                            scaleX = 1f + (LIFT_SCALE - 1f) * raised
                            scaleY = scaleX
                            if (!dark) {
                                shadowElevation = LIFT_ELEVATION.toPx() * raised
                                shape = LIFT_SHAPE
                                ambientShadowColor = SHADOW_INK
                                spotShadowColor = SHADOW_INK
                            }
                        }
                        .drawBehind {
                            val raised = lift.value
                            if (raised > 0f) {
                                drawRoundRect(
                                    color = (if (dark) palette.raised else palette.sheet).copy(alpha = raised),
                                    cornerRadius = CornerRadius(12.dp.toPx()),
                                )
                            }
                        }
                        .then(if (rows.size > 1) Modifier.semantics(mergeDescendants = true) { customActions = actions } else Modifier)
                        .then(if (rows.size > 1) reorderGesture(state, index, longPress = true) else Modifier),
                ) {
                    row(index, item, if (rows.size > 1) reorderGesture(state, index, longPress = false) else Modifier)
                }
            }
        }
    }
}

/** The keys with the one at [from] moved to [to]. */
private fun List<String>.moved(from: Int, to: Int): List<String> = toMutableList().also { it.add(to, it.removeAt(from)) }

/**
 * The gesture that lifts row [index]: at once when [longPress] is false, which is for the handle, and after the press is held when it is true, which is for
 * the whole row. The finger is followed in window coordinates, because the row moves with it and its own coordinates would not show any movement.
 */
@Composable
private fun reorderGesture(state: ReorderState, index: Int, longPress: Boolean): Modifier {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val current by rememberUpdatedState(index)
    return Modifier
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(longPress) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointer = if (longPress) (awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture).id else down.id
                fun windowY(local: Offset) = coordinates[0]?.localToWindow(local)?.y ?: 0f
                if (!state.begin(current, windowY(down.position))) return@awaitEachGesture
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pointer } ?: break
                    change.consume()
                    if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                    state.moveTo(windowY(change.position))
                }
                state.drop()
            }
        }
}

/**
 * What a drag of rows holds: where each row lies, which row is lifted, how far the finger has taken it, and the settling of it into its place. A lifted
 * row's place in the list is its [hover], the row whose middle it has crossed. Nothing changes in the list until the row has settled, when [onReorder]
 * gets the new order. The drag ends, and the rows stand where the list puts them, when that order arrives or [ORDER_WAIT_MS] have passed.
 */
private class ReorderState(
    private val scope: CoroutineScope,
    private val haptics: LumioHaptics,
    private val keys: () -> List<String>,
    private val onReorder: (List<String>) -> Unit,
    private val page: () -> PageScroll?,
    private val reduced: () -> Boolean,
) {
    var tops = FloatArray(0)
        private set
    var heights = FloatArray(0)
        private set
    var root: LayoutCoordinates? = null
    var from by mutableIntStateOf(NO_ROW)
        private set
    var startOrder by mutableStateOf<List<String>?>(null)
        private set
    var settling by mutableStateOf(false)
        private set
    private var fingerDelta by mutableFloatStateOf(0f)
    private var startFinger = 0f
    private var startScroll = 0
    private var startTop = 0f
    private var job: Job? = null

    /** How far the lifted row is from its place in the list: the finger's travel, and the page's, which carries the row's place with it. */
    val offset: Float get() = fingerDelta + scrolled()

    /** The row that the lifted row is over, or [NO_ROW] when no row is lifted. */
    val hover = derivedStateOf { if (from == NO_ROW) NO_ROW else hoverIndex(tops, heights, from, offset) }

    private fun scrolled(): Float = ((page()?.state?.value ?: 0) - startScroll).toFloat()

    fun ensureRows(count: Int) {
        if (tops.size != count) {
            tops = tops.copyOf(count)
            heights = heights.copyOf(count)
        }
    }

    fun place(index: Int, top: Float, height: Float) {
        if (index < tops.size) {
            tops[index] = top
            heights[index] = height
        }
    }

    /** Lifts row [index], with the finger at [fingerY] in the window. It does not lift while another row is lifted. */
    fun begin(index: Int, fingerY: Float): Boolean {
        if (from != NO_ROW || index !in tops.indices) return false
        job?.cancel()
        startOrder = keys()
        startFinger = fingerY
        startScroll = page()?.state?.value ?: 0
        startTop = (root?.positionInWindow()?.y ?: 0f) + tops[index]
        fingerDelta = 0f
        settling = false
        from = index
        haptics.lift()
        return true
    }

    fun moveTo(fingerY: Float) {
        if (!settling) fingerDelta = fingerY - startFinger
    }

    /** Lets go of the lifted row: it settles into the place it hovers over, and then the new order goes to [onReorder]. */
    fun drop() {
        val lifted = from
        if (lifted == NO_ROW || settling) return
        val slot = hover.value
        settling = true
        job = scope.launch {
            val goal = slotOffset(heights, lifted, slot) - scrolled()
            if (reduced()) fingerDelta = goal else animate(fingerDelta, goal, animationSpec = ClearAirMotion.reorder) { value, _ -> fingerDelta = value }
            if (slot != lifted) onReorder(keys().moved(lifted, slot))
            delay(ORDER_WAIT_MS)
            clear()
        }
    }

    fun clear() {
        job?.cancel()
        from = NO_ROW
        startOrder = null
        settling = false
        fingerDelta = 0f
    }

    /** Scrolls the page for [seconds], when the finger is near its top or bottom. */
    suspend fun autoScroll(seconds: Float, zone: Float, speed: Float) {
        val scroll = page() ?: return
        val viewport = scroll.viewport
        if (viewport.isEmpty) return
        val finger = startFinger + fingerDelta
        val pull = when {
            finger < viewport.top + zone -> -min(1f, (viewport.top + zone - finger) / zone)
            finger > viewport.bottom - zone -> min(1f, (finger - (viewport.bottom - zone)) / zone)
            else -> 0f
        }
        if (pull != 0f) scroll.state.scrollBy(pull * speed * seconds)
    }
}

/**
 * The index of the slot a dragged row has reached: row [from], moved [offset] from where it lies, is over the row whose middle it has crossed. [tops] and
 * [heights] give where each row lies, and the rows that make way are not counted at their moved places.
 */
internal fun hoverIndex(tops: FloatArray, heights: FloatArray, from: Int, offset: Float): Int {
    if (from !in tops.indices) return from
    val middle = tops[from] + heights[from] / 2f + offset
    var slot = from
    while (slot + 1 < tops.size && middle > tops[slot + 1] + heights[slot + 1] / 2f) slot++
    while (slot > 0 && middle < tops[slot - 1] + heights[slot - 1] / 2f) slot--
    return slot
}

/** How far row [index] moves to make way for row [from] when it hovers over slot [hover]: up by the dragged row's height, down by it, or not at all. */
internal fun shiftOf(index: Int, from: Int, hover: Int, heights: FloatArray): Float = when {
    from !in heights.indices || index == from -> 0f
    index in from + 1..hover -> -heights[from]
    index in hover until from -> heights[from]
    else -> 0f
}

/** How far row [from] is from where it lies when it sits in slot [slot]: the heights of the rows it passes. */
internal fun slotOffset(heights: FloatArray, from: Int, slot: Int): Float = when {
    slot > from -> (from + 1..slot).sumOf { heights[it].toDouble() }.toFloat()
    slot < from -> -(slot until from).sumOf { heights[it].toDouble() }.toFloat()
    else -> 0f
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
