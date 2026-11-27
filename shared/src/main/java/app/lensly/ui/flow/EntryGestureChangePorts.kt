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
     * Saves [gesture] as the camera's gesture. It is kept outside the vault, since the camera reads it while the vault is locked, when no
     * record can be opened. The gesture only leads to the password prompt and guards nothing.
     */
    override suspend fun save(gesture: EntryGesture): Boolean = gestures.save(gesture)
}
