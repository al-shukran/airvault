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
     * Saves [gesture] as the camera's gesture. A decoy session never changes the stored gesture: it is the real vault's setting, and a
     * coerced change in the decoy must not alter the real entry. A decoy keeps the choice in [VaultHolder.decoyShown] instead, so its
     * list marks the chosen gesture as the real vault's does, and the choice ends with the session.
     */
    override suspend fun save(gesture: EntryGesture): Boolean {
        if (holder.openSession()?.isDecoy == true) {
            holder.decoyShown.showEntryGesture(gesture)
            return true
        }
        return gestures.save(gesture)
    }
}
