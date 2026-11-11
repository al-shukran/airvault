package app.lensly.ui.flow

import app.lensly.app.EntryGestureHolder
import app.lensly.app.VaultHolder
import app.lensly.ui.camera.EntryGesture

/**
 * The app's [EntryGesturePorts] for changing the Hidden Entry Gesture. The App Password goes through [checkAppPassword], as
 * every re-entry does. The gesture is saved outside the vault, so the save needs no open session.
 */
internal class EntryGestureChangePorts(
    private val holder: VaultHolder,
    private val gestures: EntryGestureHolder,
) : EntryGesturePorts {

    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    /**
     * Saves [gesture] as the camera's gesture. A decoy session never changes it: the gesture is the real vault's setting, and a
     * coerced change in the decoy must not alter the real entry. From a decoy the save appears to succeed, and nothing is
     * written, so the screen looks the same as it does in the real vault.
     */
    override suspend fun save(gesture: EntryGesture): Boolean {
        if (holder.openSession()?.isDecoy == true) return true
        return gestures.save(gesture)
    }
}
