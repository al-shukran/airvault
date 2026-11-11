package app.lensly.ui.camera

/**
 * The Hidden Entry Gesture: the one gesture on the camera surface that begins entry to the Hidden Area. The owner picks
 * one of four. Each one starts on a fixed part of the camera surface, named by [region].
 *
 * [title] and [description] are the plain-language words Settings shows. Nothing on the camera itself says them.
 */
enum class EntryGesture(
    val storedId: Int,
    val title: String,
    val description: String,
    internal val region: EntryRegion,
) {
    /** The default: two fingers tap the viewfinder twice, then both fingers hold. */
    DOUBLE_TAP_HOLD(
        storedId = 1,
        title = "Two-finger double tap, then hold",
        description = "Tap the viewfinder twice with two fingers, then hold both fingers down.",
        region = EntryRegion.VIEWFINDER,
    ),

    /** Press and hold the gallery thumbnail, then swipe left without lifting. */
    THUMBNAIL_HOLD_SWIPE(
        storedId = 2,
        title = "Hold the gallery thumbnail, then swipe left",
        description = "Press and hold the gallery thumbnail, then swipe left without lifting your finger.",
        region = EntryRegion.THUMBNAIL,
    ),

    /** Pinch the viewfinder to the target zoom level, lift, then tap the viewfinder three times. */
    PINCH_TRIPLE_TAP(
        storedId = 3,
        title = "Pinch to 2x, then triple tap",
        description = "Pinch the viewfinder to 2x zoom, lift your fingers, then tap the viewfinder three times.",
        region = EntryRegion.VIEWFINDER,
    ),

    /** Switch the camera three times, then press and hold the shutter. */
    SWITCH_THEN_SHUTTER_HOLD(
        storedId = 4,
        title = "Switch camera three times, then hold the shutter",
        description = "Tap the camera switch three times, then press and hold the shutter.",
        region = EntryRegion.SHUTTER,
    );

    companion object {
        /** The gesture used when nothing is stored, or the stored value is not one of the four. */
        val DEFAULT: EntryGesture = DOUBLE_TAP_HOLD

        fun fromStoredId(id: Int): EntryGesture = entries.firstOrNull { it.storedId == id } ?: DEFAULT
    }
}
