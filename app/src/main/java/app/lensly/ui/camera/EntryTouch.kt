package app.lensly.ui.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates

/** Where a touch started on a viewfinder screen: the viewfinder, the gallery thumbnail, the shutter, or elsewhere. */
internal enum class EntryRegion { VIEWFINDER, THUMBNAIL, SHUTTER, OTHER }

/** One pointer event of a touch that started in the gesture's own region, as [EntryTouch.activeEvent] reads it. */
internal data class TouchFrame(
    /** Fingers down now. */
    val count: Int,
    /** Fingers down before this event. */
    val pressed: Int,
    /** Where the first finger still down is, or null when none is. */
    val position: Offset?,
    /** Event time, in monotonic milliseconds. */
    val timeMs: Long,
    /** True once any finger of this touch has left the slop around where it landed. */
    val moved: Boolean,
    /** The most fingers down at once during this touch. */
    val peak: Int,
    /** Time from the first finger down to this event. */
    val contactMs: Long,
)

/**
 * The entry-gesture state of one viewfinder screen, the camera or the practice pad. It holds the detector of the chosen
 * [gesture], where the screen's controls are in pointer coordinates, and whether the shutter click that ends a completed
 * gesture must be ignored. The screen keeps one of these for its whole life. [select] changes the gesture in place, so the
 * control positions survive a change of gesture.
 */
internal class EntryTouch(
    private val slopPx: Float,
    private val swipePx: Float,
) {
    /** The gesture this screen listens for now. [select] changes it. */
    var gesture: EntryGesture = EntryGesture.DEFAULT
        private set

    private var hidden = HiddenGestureDetector()
    private var swipe = ThumbnailSwipeDetector(slopPx = slopPx, swipePx = swipePx)
    private var zoomTaps = PinchTripleTapDetector()
    private var shutter = ModeShutterHoldDetector()

    /** The screen's own coordinates. Pointer positions are in these. */
    var root: LayoutCoordinates? = null

    /** The viewfinder area, in pointer coordinates. */
    var viewfinder: Rect? = null

    private var thumbnail: LayoutCoordinates? = null
    private var shutterControl: LayoutCoordinates? = null
    private var shutterClickIgnored = false

    /**
     * Listens for [chosen] from now on. Any progress of the gesture before it is dropped. Control positions are kept, since
     * they belong to the screen and not to the gesture.
     */
    fun select(chosen: EntryGesture) {
        if (chosen == gesture) return
        gesture = chosen
        hidden = HiddenGestureDetector()
        swipe = ThumbnailSwipeDetector(slopPx = slopPx, swipePx = swipePx)
        zoomTaps = PinchTripleTapDetector()
        shutter = ModeShutterHoldDetector()
        shutterClickIgnored = false
    }

    /** Records where a control sits, so touches that start on it belong to its region. */
    fun placeControl(region: EntryRegion, coords: LayoutCoordinates) {
        when (region) {
            EntryRegion.THUMBNAIL -> thumbnail = coords
            EntryRegion.SHUTTER -> shutterControl = coords
            EntryRegion.VIEWFINDER, EntryRegion.OTHER -> Unit
        }
    }

    /** The region a touch that starts at [position] belongs to. */
    fun regionOf(position: Offset): EntryRegion = when {
        viewfinder?.contains(position) == true -> EntryRegion.VIEWFINDER
        boundsIn(thumbnail)?.contains(position) == true -> EntryRegion.THUMBNAIL
        boundsIn(shutterControl)?.contains(position) == true -> EntryRegion.SHUTTER
        else -> EntryRegion.OTHER
    }

    /** The camera switch was pressed. Only the shutter gesture counts it. */
    fun onModeSwitch(timeMs: Long) {
        if (gesture == EntryGesture.SWITCH_THEN_SHUTTER_HOLD) shutter.onModeSwitch(timeMs)
    }

    /** A touch started in [region] at [position]. The gesture's detector starts only when the touch is in its own region. */
    fun touchStarted(region: EntryRegion, position: Offset, timeMs: Long) {
        if (region == EntryRegion.SHUTTER) shutterClickIgnored = false
        if (region != gesture.region) return
        when (gesture) {
            EntryGesture.DOUBLE_TAP_HOLD -> hidden.onDown(1, timeMs)
            EntryGesture.THUMBNAIL_HOLD_SWIPE -> swipe.onDown(position.x, position.y, timeMs)
            EntryGesture.PINCH_TRIPLE_TAP -> Unit
            EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> shutter.onShutterDown(timeMs)
        }
    }

    /** The time the loop must wake for, though no pointer moves, or null when no hold is running. */
    fun wakeAtMs(): Long? = when (gesture) {
        EntryGesture.DOUBLE_TAP_HOLD -> hidden.holdCompleteAtMs
        EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> shutter.holdCompleteAtMs
        EntryGesture.THUMBNAIL_HOLD_SWIPE, EntryGesture.PINCH_TRIPLE_TAP -> null
    }

    /** Time passed with no pointer event. Returns true when a hold completes. */
    fun tick(timeMs: Long): Boolean = when (gesture) {
        EntryGesture.DOUBLE_TAP_HOLD -> hidden.onTick(timeMs)
        EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> shutter.onTick(timeMs)
        EntryGesture.THUMBNAIL_HOLD_SWIPE, EntryGesture.PINCH_TRIPLE_TAP -> false
    }

    /** A pointer event of a touch in the gesture's own region. Returns true when the gesture completes on it. */
    fun activeEvent(frame: TouchFrame): Boolean = when (gesture) {
        EntryGesture.DOUBLE_TAP_HOLD -> when {
            frame.count > frame.pressed -> hidden.onDown(frame.count, frame.timeMs)
            frame.count < frame.pressed -> hidden.onUp(frame.count, frame.timeMs)
            else -> false
        }
        EntryGesture.THUMBNAIL_HOLD_SWIPE -> {
            val at = frame.position
            if (frame.count == 1 && at != null) {
                swipe.onMove(at.x, at.y, frame.timeMs)
            } else {
                swipe.cancel()
                false
            }
        }
        EntryGesture.PINCH_TRIPLE_TAP -> when {
            frame.count != 0 -> false
            frame.peak == 1 && !frame.moved -> zoomTaps.onTap(frame.timeMs, frame.contactMs)
            else -> {
                zoomTaps.onOtherTouch()
                false
            }
        }
        EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> when {
            frame.count == 0 -> shutter.onShutterUp(frame.timeMs)
            frame.count > 1 || frame.moved -> {
                shutter.cancel()
                false
            }
            else -> false
        }
    }

    /** A pinch or drag moved a touch of the gesture's region. The gesture's progress in that touch ends. */
    fun cancelGesture() {
        when (gesture) {
            EntryGesture.DOUBLE_TAP_HOLD -> hidden.cancel()
            EntryGesture.THUMBNAIL_HOLD_SWIPE -> swipe.cancel()
            EntryGesture.PINCH_TRIPLE_TAP -> zoomTaps.onOtherTouch()
            EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> shutter.cancel()
        }
    }

    /** A pinch started on the viewfinder. */
    fun pinchStarted() {
        if (gesture == EntryGesture.PINCH_TRIPLE_TAP) zoomTaps.onPinchStart()
    }

    /** A pinch on the viewfinder ended with the zoom at [zoomRatio]. */
    fun pinchEnded(zoomRatio: Float, timeMs: Long) {
        if (gesture == EntryGesture.PINCH_TRIPLE_TAP) zoomTaps.onPinchEnd(zoomRatio, timeMs)
    }

    /** The gesture completed. The shutter click that ends the same touch must not take a photo. */
    fun completed() {
        shutterClickIgnored = true
    }

    /** True once, for the shutter click that ends a touch on which a gesture completed. The click then takes no photo. */
    fun consumeShutterClick(): Boolean {
        val ignored = shutterClickIgnored
        shutterClickIgnored = false
        return ignored
    }

    private fun boundsIn(coords: LayoutCoordinates?): Rect? {
        val screen = root ?: return null
        if (coords == null || !coords.isAttached || !screen.isAttached) return null
        return screen.localBoundingBoxOf(coords)
    }
}
