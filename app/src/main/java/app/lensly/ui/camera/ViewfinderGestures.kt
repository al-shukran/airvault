package app.lensly.ui.camera

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pointer handling for a viewfinder screen, the camera or the practice pad. Each touch is classified by where it starts.
 *
 * - A single-finger tap on the viewfinder calls [onFocus] with the touch position.
 * - A two-finger pinch on the viewfinder calls [onZoom] with the span scale since the last event.
 * - The chosen gesture of [touch] sees the touches that start in its own region, and no others.
 *
 * Nothing is consumed here, so the controls keep their own clicks. [onHiddenEntry] runs once for each completed gesture.
 */
internal suspend fun PointerInputScope.detectEntryGestures(
    touch: EntryTouch,
    zoomRatio: () -> Float,
    onFocus: (Offset) -> Unit,
    onZoom: (scale: Float) -> Unit,
    onHiddenEntry: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val region = touch.regionOf(first.position)
        val inViewfinder = region == EntryRegion.VIEWFINDER
        val active = region == touch.gesture.region
        val downTime = first.uptimeMillis
        val downAt = HashMap<PointerId, Offset>()
        downAt[first.id] = first.position
        var pressed = 1
        var peak = 1
        var moved = false
        var pinching = false
        var startSpan = -1f
        var lastSpan = -1f
        touch.touchStarted(region, first.position, downTime)

        while (true) {
            // While a hold is in progress, wake up when it completes even if no finger moves.
            val wakeAt = if (active) touch.wakeAtMs() else null
            val event = if (wakeAt == null) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(wakeAt - SystemClock.uptimeMillis()) { awaitPointerEvent() }
            }
            if (event == null) {
                if (active && touch.tick(SystemClock.uptimeMillis())) {
                    touch.completed()
                    onHiddenEntry()
                }
                continue
            }

            val time = event.changes.first().uptimeMillis
            val down = event.changes.filter { it.pressed }
            down.forEach { change -> downAt.getOrPut(change.id) { change.position } }
            val count = down.size
            if (!moved) {
                moved = down.any { change -> (change.position - downAt.getValue(change.id)).getDistance() > slop }
            }
            peak = maxOf(peak, count)

            var completed = false
            if (active) {
                val frame = TouchFrame(
                    count = count,
                    pressed = pressed,
                    position = down.firstOrNull()?.position,
                    timeMs = time,
                    moved = moved,
                    peak = peak,
                    contactMs = time - downTime,
                )
                completed = touch.activeEvent(frame)
            }
            pressed = count

            if (count >= 2) {
                val span = (down[0].position - down[1].position).getDistance()
                if (startSpan < 0f) {
                    startSpan = span
                } else if (!pinching && abs(span - startSpan) > slop) {
                    pinching = true
                    if (inViewfinder) touch.pinchStarted()
                }
                if (pinching) {
                    moved = true
                    if (inViewfinder && lastSpan > 0f && span > 0f) onZoom(span / lastSpan)
                }
                if (moved && active) touch.cancelGesture()
                lastSpan = span
            } else {
                if (pinching && inViewfinder) touch.pinchEnded(zoomRatio(), time)
                startSpan = -1f
                lastSpan = -1f
                pinching = false
            }

            if (completed) {
                touch.completed()
                onHiddenEntry()
            }
            if (count == 0) {
                if (inViewfinder && peak == 1 && !moved) onFocus(first.position)
                break
            }
        }
    }
}
