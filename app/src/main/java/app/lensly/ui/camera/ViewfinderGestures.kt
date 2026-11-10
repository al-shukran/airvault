package app.lensly.ui.camera

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pointer handling for the viewfinder.
 *
 * - A single-finger tap calls [onFocus] with the touch position.
 * - A two-finger pinch calls [onZoom] with the span scale since the last event.
 * - Every touch is also fed to [detector] for the hidden entry gesture, which is never shown.
 *
 * Only touches that start inside [viewfinderBounds] count, so the controls keep their own clicks.
 * Nothing is consumed here.
 */
internal suspend fun PointerInputScope.detectViewfinderGestures(
    detector: HiddenGestureDetector,
    viewfinderBounds: () -> Rect?,
    onFocus: (Offset) -> Unit,
    onZoom: (scale: Float) -> Unit,
    onHiddenEntry: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val active = viewfinderBounds()?.contains(first.position) == true
        val downAt = HashMap<PointerId, Offset>()
        downAt[first.id] = first.position
        var pressed = 1
        var peak = 1
        var moved = false
        var pinching = false
        var startSpan = -1f
        var lastSpan = -1f
        if (active) detector.onDown(1, first.uptimeMillis)

        while (true) {
            // While a hold is in progress, wake up when it completes even if no finger moves.
            val holdEndsAt = if (active) detector.holdCompleteAtMs else null
            val event = if (holdEndsAt == null) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(holdEndsAt - SystemClock.uptimeMillis()) { awaitPointerEvent() }
            }
            if (event == null) {
                if (detector.onTick(SystemClock.uptimeMillis())) onHiddenEntry()
                continue
            }

            val time = event.changes.first().uptimeMillis
            val down = event.changes.filter { it.pressed }
            down.forEach { change -> downAt.getOrPut(change.id) { change.position } }
            val count = down.size

            if (active) {
                val completed = when {
                    count > pressed -> detector.onDown(count, time)
                    count < pressed -> detector.onUp(count, time)
                    else -> false
                }
                if (completed) onHiddenEntry()
            }
            pressed = count
            peak = maxOf(peak, count)

            if (!moved) {
                moved = down.any { change ->
                    (change.position - downAt.getValue(change.id)).getDistance() > slop
                }
            }

            if (count >= 2) {
                val span = (down[0].position - down[1].position).getDistance()
                if (startSpan < 0f) {
                    startSpan = span
                } else if (!pinching && abs(span - startSpan) > slop) {
                    pinching = true
                }
                if (pinching) {
                    moved = true
                    if (active && lastSpan > 0f && span > 0f) onZoom(span / lastSpan)
                }
                if (moved && active) detector.cancel()
                lastSpan = span
            } else {
                startSpan = -1f
                lastSpan = -1f
                pinching = false
            }

            if (count == 0) {
                if (active && peak == 1 && !moved) onFocus(first.position)
                break
            }
        }
    }
}
