package app.lensly.ui.camera

/** What the shutter does: take a photo, or record a video. */
enum class CameraMode { PHOTO, VIDEO }

/**
 * Whether to show the microphone prompt when the owner enters video mode. It is shown once, and never when the microphone
 * is already allowed. A refusal leaves video recording without sound.
 */
internal fun shouldAskForMicrophone(granted: Boolean, alreadyAsked: Boolean): Boolean = !granted && !alreadyAsked
