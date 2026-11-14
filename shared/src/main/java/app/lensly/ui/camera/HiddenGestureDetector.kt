package app.lensly.ui.camera

/**
 * Pure state machine for the hidden entry gesture:
 *
 * 1. a two-finger tap,
 * 2. a second two-finger tap that starts within [tapWindowMs] of the first release,
 * 3. two fingers held down for at least [holdMs], starting within [tapWindowMs] of the second release.
 *
 * Each tap must be a short contact of at most [tapMaxMs]. A lone-finger touch, a third finger,
 * or a [cancel] (pinch or drag) breaks the sequence. The detector shows, logs and vibrates nothing.
 *
 * Feed it from the pointer layer. Times are monotonic milliseconds (for example
 * `SystemClock.uptimeMillis()`); the caller chooses the clock.
 */
class HiddenGestureDetector(
    private val tapWindowMs: Long = 400L,
    private val tapMaxMs: Long = 300L,
    private val holdMs: Long = 1_200L,
) {
    private enum class Stage { IDLE, FIRST_DOWN, FIRST_UP, SECOND_DOWN, SECOND_UP, HOLD }

    private var stage = Stage.IDLE
    private var stageEndMs = 0L // release time of the last tap (FIRST_UP, SECOND_UP)
    private var contactStartMs = 0L // when the current two-finger contact began
    private var twoFingerContact = false // physical state: two or more fingers are down
    private var touchPeak = 0 // most fingers down during the current touch

    /** Moment the hold completes, or null when no hold is in progress. Use it as a timer deadline. */
    val holdCompleteAtMs: Long?
        get() = if (stage == Stage.HOLD) contactStartMs + holdMs else null

    /**
     * A finger landed. [pressedCount] is how many fingers are now down.
     * Returns true only if the gesture completes on this call.
     */
    fun onDown(pressedCount: Int, timeMs: Long): Boolean {
        touchPeak = maxOf(touchPeak, pressedCount)
        if (pressedCount >= 3) {
            reset()
            return false
        }
        if (pressedCount < 2 || twoFingerContact) return false

        twoFingerContact = true
        contactStartMs = timeMs
        stage = when (stage) {
            Stage.FIRST_UP ->
                if (timeMs - stageEndMs <= tapWindowMs) Stage.SECOND_DOWN else Stage.FIRST_DOWN
            Stage.SECOND_UP ->
                if (timeMs - stageEndMs <= tapWindowMs) Stage.HOLD else Stage.FIRST_DOWN
            else -> Stage.FIRST_DOWN
        }
        return false
    }

    /**
     * A finger lifted. [pressedCount] is how many fingers remain down.
     * Returns true only if the gesture completes on this call (a hold that ended after [holdMs]).
     */
    fun onUp(pressedCount: Int, timeMs: Long): Boolean {
        var completed = false
        if (twoFingerContact && pressedCount < 2) {
            twoFingerContact = false
            val contactMs = timeMs - contactStartMs
            val isTap = contactMs <= tapMaxMs
            when (stage) {
                Stage.FIRST_DOWN ->
                    if (isTap) {
                        stage = Stage.FIRST_UP
                        stageEndMs = timeMs
                    } else {
                        reset()
                    }
                Stage.SECOND_DOWN ->
                    if (isTap) {
                        stage = Stage.SECOND_UP
                        stageEndMs = timeMs
                    } else {
                        reset()
                    }
                Stage.HOLD -> {
                    completed = contactMs >= holdMs
                    reset()
                }
                else -> reset()
            }
        }
        if (pressedCount == 0) {
            // A touch with a single finger is not part of the gesture.
            if (touchPeak == 1) reset()
            touchPeak = 0
        }
        return completed
    }

    /** Time passed with no pointer event. Returns true when a hold reaches [holdMs] while still down. */
    fun onTick(timeMs: Long): Boolean {
        if (stage == Stage.HOLD && timeMs >= contactStartMs + holdMs) {
            reset()
            return true
        }
        return false
    }

    /** A pinch or drag started, so the current touch is not a tap or a hold. */
    fun cancel() {
        reset()
    }

    private fun reset() {
        stage = Stage.IDLE
    }
}
