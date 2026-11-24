package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.LocalMonoFamily
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A key held this long opens its alternates. It matches the key repeat's start, so a held key acts at one pace. */
internal const val ALTERNATES_LONG_PRESS_MS = 400L

/** Test tag of the open strip. It carries no text, so the characters stay out of accessibility. */
internal const val KEY_ALTERNATES_TAG = "keyboard-alternates"

/** The side of one alternate's cell, in dp. */
internal val ALTERNATE_CELL = 44.dp
private val ALTERNATE_TEXT_SIZE = 22.sp
private val ALTERNATES_SHAPE = RoundedCornerShape(10.dp)

/**
 * What a long press offers, keyed by the base character. Symbols are the ones AOSP LatinIME's key definitions give (the dash, the
 * equals sign, the caret, the degree sign, pi, the pilcrow, and the period). Letters take their common accents, with e as the
 * primary example. Digits have none, since the number row is always on screen.
 */
internal val ALTERNATES: Map<Char, String> = mapOf(
    'a' to "àáâäæãåā",
    'c' to "çćč",
    'e' to "èéêëēėę",
    'i' to "ìíîïī",
    'n' to "ñń",
    'o' to "òóôöõøœō",
    's' to "śšß",
    'u' to "ùúûüū",
    'y' to "ÿý",
    'z' to "žźż",
    '-' to "_–—·",
    '=' to "≠≈∞",
    '^' to "↑↓←→",
    '°' to "′″",
    'π' to "Π",
    '¶' to "§",
    '.' to "…",
)

/** The index of the alternate under [x], clamped to the strip, for a strip of [count] cells of [cell] px that starts at [left]. */
internal fun alternateIndex(x: Float, left: Float, cell: Float, count: Int): Int =
    floor((x - left) / cell).toInt().coerceIn(0, count - 1)

/** The left edge of a strip [width] px wide, centred on [centre] and kept inside a container [containerWidth] px wide. */
internal fun alternateStripLeft(centre: Float, width: Float, containerWidth: Float): Float =
    (centre - width / 2f).coerceIn(0f, (containerWidth - width).coerceAtLeast(0f))

/** An open strip, in the keyboard's coordinates. [selected] is the alternate under the finger, or -1 for none. */
internal data class OpenAlternates(
    val left: Float,
    val top: Float,
    val keyTop: Float,
    val cell: Float,
    val chars: String,
    val selected: Int = -1,
)

/**
 * Holds the one strip of alternates a keyboard shows. A key opens it on a long press, follows the finger while it is held, and takes
 * the alternate under the finger on release. Positions are in the keyboard's own coordinates, so the key and the strip compare
 * directly.
 */
internal class AlternatesPicker {
    var open: OpenAlternates? by mutableStateOf(null)
        private set

    private var keyboard: LayoutCoordinates? = null

    fun attach(coordinates: LayoutCoordinates) {
        keyboard = coordinates
    }

    /** Opens [chars] above [face], the key as drawn. Does nothing until the keyboard has been placed. */
    fun begin(face: LayoutCoordinates, chars: String, cell: Float) {
        val board = keyboard ?: return
        val origin = board.localPositionOf(face, Offset.Zero)
        val width = chars.length * cell
        open = OpenAlternates(
            left = alternateStripLeft(origin.x + face.size.width / 2f, width, board.size.width.toFloat()),
            top = origin.y - cell,
            keyTop = origin.y,
            cell = cell,
            chars = chars,
        )
    }

    /** Moves the choice to the alternate under [local], a point in [key]'s coordinates. Only a point above the key chooses. */
    fun track(key: LayoutCoordinates, local: Offset) {
        val board = keyboard ?: return
        val strip = open ?: return
        val point = board.localPositionOf(key, local)
        val selected = if (point.y < strip.keyTop) {
            alternateIndex(point.x, strip.left, strip.cell, strip.chars.length)
        } else {
            -1
        }
        open = strip.copy(selected = selected)
    }

    /** Closes the strip and returns the alternate under the finger, or null when there is none. */
    fun end(): Char? {
        val strip = open
        close()
        return strip?.let { if (it.selected >= 0) it.chars[it.selected] else null }
    }

    fun close() {
        open = null
    }
}

/**
 * Where a key sits in its keyboard, set once the key is placed. [cell] is its touch area, which takes the gaps beside it, and [face] is
 * the key as drawn, inside that area. Its gesture reads them to convert positions.
 */
internal class KeyPlace {
    var cell: LayoutCoordinates? = null
    var face: LayoutCoordinates? = null
}

/**
 * The strip of alternates while one is open, drawn over the rows above the key. It is a layer of the keyboard, not a popup, so it
 * shares the keyboard's window and its FLAG_SECURE. Its semantics are cleared, so the characters are not announced.
 */
@Composable
internal fun AlternatesStrip(picker: AlternatesPicker) {
    val strip = picker.open ?: return
    val cell = with(LocalDensity.current) { strip.cell.toDp() }
    Row(
        modifier = Modifier
            .offset { IntOffset(strip.left.roundToInt(), strip.top.roundToInt()) }
            .clearAndSetSemantics { testTag = KEY_ALTERNATES_TAG }
            .clip(ALTERNATES_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        strip.chars.forEachIndexed { index, char ->
            val chosen = index == strip.selected
            Box(
                modifier = Modifier
                    .size(cell)
                    .background(if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = char.toString(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalMonoFamily.current, fontSize = ALTERNATE_TEXT_SIZE),
                    color = if (chosen) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The press of a key that has [alternates]. Released before [ALTERNATES_LONG_PRESS_MS] and still on the key, it is a tap and calls
 * [onTap]. Held past that, it opens the strip, which follows the finger, and release calls [onChoose] with the alternate under the
 * finger, if any. A finger that leaves the key before the long press cancels the tap. The press shows on [interactionSource], as a
 * click does.
 */
internal fun Modifier.alternatesGesture(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    place: KeyPlace,
    picker: AlternatesPicker,
    alternates: String,
    cell: Float,
    onTap: () -> Unit,
    onChoose: (Char) -> Unit,
): Modifier = pointerInput(enabled, alternates) {
    if (!enabled) return@pointerInput
    // The long-press timer is a child of this scope. It only sets the strip open, so the pointer loop never has to wait on it.
    coroutineScope {
        awaitEachGesture {
            val key = place.cell ?: return@awaitEachGesture
            val face = place.face ?: return@awaitEachGesture
            val down = awaitFirstDown()
            down.consume()
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            val bounds = key.size
            fun inside(point: Offset): Boolean =
                point.x in 0f..bounds.width.toFloat() && point.y in 0f..bounds.height.toFloat()

            var opened = false
            var lifted = false
            var tapped = false
            var choice: Char? = null
            val timer = this@coroutineScope.launch {
                delay(ALTERNATES_LONG_PRESS_MS)
                picker.begin(face, alternates, cell)
                opened = true
            }
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        lifted = true
                        if (opened) choice = picker.end() else tapped = inside(change.position)
                        break
                    }
                    if (opened) {
                        picker.track(key, change.position)
                    } else if (!inside(change.position)) {
                        // A finger that leaves the key before the long press cancels the tap.
                        break
                    }
                    change.consume()
                }
            } finally {
                timer.cancel()
                picker.close()
                interactionSource.tryEmit(if (lifted) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
            if (tapped) onTap()
            choice?.let(onChoose)
        }
    }
}
