package app.lensly.ui.flow

import app.lensly.app.EntryGestureHolder
import app.lensly.app.VaultHolder
import app.lensly.ui.camera.EntryGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
     * coerced change in the decoy must not alter the real entry. A decoy keeps the choice in [VaultHolder.decoyShown] instead, in its
     * own vault record, so its list marks the chosen gesture as the real vault's does, in this session and in later ones.
     */
    override suspend fun save(gesture: EntryGesture): Boolean {
        if (holder.openSession()?.isDecoy == true) {
            return withContext(Dispatchers.IO + NonCancellable) { holder.decoyShown.showEntryGesture(gesture) }
        }
        return gestures.save(gesture)
    }
}
