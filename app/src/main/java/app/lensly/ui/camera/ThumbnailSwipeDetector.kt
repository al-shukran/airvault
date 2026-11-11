package app.lensly.ui.camera

import kotlin.math.abs

/**
 * Pure state machine for the thumbnail gesture: press and hold the gallery thumbnail, then, without lifting, swipe left.
 *
 * 1. A finger lands on the thumbnail and stays within [slopPx] of where it landed until [longPressMs] has passed.
 * 2. Then it moves left by at least [swipePx], mostly sideways. Before the long press completes, a move beyond [slopPx]
 *    is a drag and cancels the gesture. After it, a move to the right cancels too.
 *
 * Positions are in pixels and times are monotonic milliseconds. The detector shows, logs and vibrates nothing.
 */
class ThumbnailSwipeDetector(
    private val slopPx: Float,
    private val swipePx: Float,
    private val longPressMs: Long = 600L,
) {
    private enum class Stage { IDLE, PRESSING, HELD }

    private var stage = Stage.IDLE
    private var downAtMs = 0L
    private var downX = 0f
    private var downY = 0f

    /** A finger landed on the thumbnail. */
    fun onDown(x: Float, y: Float, timeMs: Long) {
        stage = Stage.PRESSING
        downAtMs = timeMs
        downX = x
        downY = y
    }

    /**
     * The pressing finger is now at ([x], [y]). Returns true only if the swipe completes on this call.
     */
    fun onMove(x: Float, y: Float, timeMs: Long): Boolean {
        if (stage == Stage.IDLE) return false
        val dx = x - downX
        val dy = y - downY
        if (stage == Stage.PRESSING) {
            if (dx * dx + dy * dy <= slopPx * slopPx) return false
            // A drag before the long press is complete is not this gesture.
            if (timeMs - downAtMs < longPressMs) return cancelled()
            stage = Stage.HELD
        }
        // Held: a leftward swipe completes it, and a move to the right cancels it.
        if (dx > slopPx) return cancelled()
        if (-dx >= swipePx && abs(dy) < -dx) {
            stage = Stage.IDLE
            return true
        }
        return false
    }

    /** The finger lifted, or a second finger landed. Nothing completes. */
    fun cancel() {
        stage = Stage.IDLE
    }

    private fun cancelled(): Boolean {
        stage = Stage.IDLE
        return false
    }
}
