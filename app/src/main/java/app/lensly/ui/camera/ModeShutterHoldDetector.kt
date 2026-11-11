package app.lensly.ui.camera

/**
 * Pure state machine for the shutter gesture: switch the camera [switchesRequired] times, then press and hold the shutter.
 *
 * 1. The camera switch is pressed [switchesRequired] times, each within [windowMs] of the one before. That arms the shutter.
 *    A switch after the sequence is complete disarms it.
 * 2. The shutter must be pressed within [windowMs] of the last switch, and held for [holdMs] without moving. The hold
 *    completes while the finger is still down, so the release is not taken for a photo.
 *
 * Any shutter press uses up the arming, so an ordinary photo never completes it. Times are monotonic milliseconds. The
 * detector shows, logs and vibrates nothing.
 */
class ModeShutterHoldDetector(
    private val switchesRequired: Int = 3,
    private val windowMs: Long = 3_000L,
    private val holdMs: Long = 800L,
) {
    private var switches = 0
    private var lastSwitchMs = 0L
    private var armedAtMs: Long? = null
    private var holdStartMs: Long? = null

    /** Moment the hold completes, or null when no hold is in progress. Use it as a timer deadline. */
    val holdCompleteAtMs: Long?
        get() = holdStartMs?.let { it + holdMs }

    /** The camera switch was pressed. */
    fun onModeSwitch(timeMs: Long) {
        if (switches > 0 && timeMs - lastSwitchMs > windowMs) switches = 0
        switches++
        lastSwitchMs = timeMs
        armedAtMs = null
        holdStartMs = null
        if (switches >= switchesRequired) {
            switches = 0
            armedAtMs = timeMs
        }
    }

    /** The shutter was pressed. It starts a hold only if the switches armed it and the press comes in time. */
    fun onShutterDown(timeMs: Long) {
        val armed = armedAtMs
        armedAtMs = null
        holdStartMs = if (armed != null && timeMs - armed <= windowMs) timeMs else null
    }

    /** Time passed while the shutter is still held. Returns true when the hold reaches [holdMs]. */
    fun onTick(timeMs: Long): Boolean {
        val start = holdStartMs ?: return false
        if (timeMs - start < holdMs) return false
        holdStartMs = null
        return true
    }

    /** The shutter was released. Returns true if the hold had already reached [holdMs]. */
    fun onShutterUp(timeMs: Long): Boolean {
        val start = holdStartMs ?: return false
        holdStartMs = null
        return timeMs - start >= holdMs
    }

    /** The touch moved, or a second finger landed, so it is not a hold. */
    fun cancel() {
        holdStartMs = null
    }
}
