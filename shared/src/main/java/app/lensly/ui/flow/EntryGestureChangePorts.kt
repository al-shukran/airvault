package app.lensly.ui.flow

import app.lensly.app.EntryGestureHolder
import app.lensly.app.VaultHolder
import app.lensly.ui.camera.EntryGesture

/**
 * The app's [EntryGesturePorts] for changing the Hidden Entry Gesture. The App Password goes through [checkAppPassword], as
 * every re-entry does. The gesture is saved outside the vault, so the save needs no open session.
 */
class EntryGestureChangePorts(
    private val holder: VaultHolder,
    private val gestures: EntryGestureHolder,
) : EntryGesturePorts {

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    /**
     * Saves [gesture] as the camera's gesture. A decoy session saves it too, as a real session does: the camera reads the gesture while
     * the vault is locked, when no decoy record can be opened, so a gesture kept only for the decoy would not work there, and a plain
     * file that marks a decoy would give the decoy away. The gesture only leads to the password prompt and guards nothing, and whoever
     * demanded the change already knows the old one, so the owner's real entry works with the new gesture.
     */
    override suspend fun save(gesture: EntryGesture): Boolean = gestures.save(gesture)
}
