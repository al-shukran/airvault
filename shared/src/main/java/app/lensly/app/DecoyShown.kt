package app.lensly.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.core.vault.FailureDelay
import app.lensly.ui.camera.EntryGesture

/**
 * What a decoy session shows for the settings that live outside its vault and belong to the real one: the Hidden Entry Gesture and the
 * wrong password lockout. A decoy never writes them and never reads them. A change made in a decoy is kept here instead, so the screen
 * shows the result a real vault shows. Before any change it shows the defaults, so the real values stay out of the decoy.
 *
 * It lives in memory, and [VaultHolder] clears it on every lock and every new session, so nothing of a decoy's choice survives either.
 */
class DecoyShown {
    private var gesture by mutableStateOf<EntryGesture?>(null)

    /** The lockout cap in milliseconds the decoy shows, or null for Default. */
    var failureCapMs: Long? = null
        private set

    /** The erase count the decoy shows. */
    var failureEraseAfter: Int = FailureDelay.DEFAULT_WIPE_AFTER
        private set

    /** The gesture the decoy shows as current: its own choice, or [real] while it has made none. */
    fun entryGesture(real: EntryGesture): EntryGesture = gesture ?: real

    fun showEntryGesture(chosen: EntryGesture) {
        gesture = chosen
    }

    fun showFailureLimits(capMs: Long?, eraseAfter: Int) {
        failureCapMs = capMs
        failureEraseAfter = eraseAfter
    }

    internal fun clear() {
        gesture = null
        failureCapMs = null
        failureEraseAfter = FailureDelay.DEFAULT_WIPE_AFTER
    }
}
