package app.lensly.ui.camera

import kotlin.math.abs

/**
 * Pure state machine for the zoom gesture: pinch the viewfinder to a precise zoom level, lift, then tap the viewfinder
 * [tapsRequired] times.
 *
 * 1. A pinch that ends within [zoomTolerance] of [targetZoom] arms the gesture. Any other pinch disarms it.
 * 2. While armed, single-finger taps count. Each tap must be quick (at most [tapMaxMs] of contact), and each must follow
 *    the one before within [tapWindowMs]. All of them must come within [armWindowMs] of the pinch.
 *
 * A pinch or any other touch breaks a run of taps. Ordinary single taps at any zoom never complete the gesture, because
 * the owner has to pinch to the level first. Times are monotonic milliseconds. The detector shows, logs and vibrates
 * nothing.
 */
class PinchTripleTapDetector(
    private val targetZoom: Float = 2f,
    private val zoomTolerance: Float = 0.1f,
    private val armWindowMs: Long = 10_000L,
    private val tapWindowMs: Long = 600L,
    private val tapMaxMs: Long = 300L,
    private val tapsRequired: Int = 3,
) {
    private var armedAtMs: Long? = null
    private var taps = 0
    private var lastTapAtMs = 0L

    /** A pinch started, so the zoom is moving and any earlier arming no longer counts. */
    fun onPinchStart() {
        disarm()
    }

    /** A pinch ended with the zoom at [zoomRatio]. It arms the gesture only when the zoom is at the target. */
    fun onPinchEnd(zoomRatio: Float, timeMs: Long) {
        taps = 0
        armedAtMs = if (abs(zoomRatio - targetZoom) <= zoomTolerance) timeMs else null
    }

    /**
     * A single finger lifted from the viewfinder without moving, after [contactMs] of contact. Returns true when the last
     * of the required taps completes the gesture.
     */
    fun onTap(upAtMs: Long, contactMs: Long): Boolean {
        if (contactMs > tapMaxMs) {
            // A slow press is not a tap, so the run of taps starts again.
            taps = 0
            return false
        }
        val armed = armedAtMs ?: return false
        if (upAtMs - armed > armWindowMs) {
            disarm()
            return false
        }
        if (taps > 0 && upAtMs - lastTapAtMs > tapWindowMs) taps = 0
        taps++
        lastTapAtMs = upAtMs
        if (taps < tapsRequired) return false
        disarm()
        return true
    }

    /** Any other touch, such as a hold, a drag or two fingers. It breaks a run of taps but not the arming. */
    fun onOtherTouch() {
        taps = 0
    }

    private fun disarm() {
        armedAtMs = null
        taps = 0
    }
}
