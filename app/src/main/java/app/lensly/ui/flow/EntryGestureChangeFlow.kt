package app.lensly.ui.flow

import app.lensly.ui.camera.EntryGesture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a change of the Hidden Entry Gesture ended. */
enum class EntryGestureOutcome {
    /** The new gesture works and is saved. The screen goes back to the gesture list. */
    SAVED,

    /** The gesture could not be saved, so nothing is claimed as changed. */
    UNAVAILABLE,
}

/**
 * Where a change of the Hidden Entry Gesture is: the App Password, then the practice of the new gesture, then the outcome.
 */
sealed interface EntryGestureStep {
    /** The App Password is asked. [busy] while the check runs. A wrong password returns here with nothing shown. */
    data class Password(val busy: Boolean) : EntryGestureStep

    /**
     * The password was right. The owner performs the new gesture on the practice surface. [busy] while it is saved.
     * Nothing is saved before the gesture has worked.
     */
    data class Practice(val busy: Boolean) : EntryGestureStep

    /** The change has ended, and [outcome] says how. */
    data class Ended(val outcome: EntryGestureOutcome) : EntryGestureStep
}

/** What [EntryGestureChangeFlow] asks of the app. [EntryGestureChangePorts] holds the app's implementation, and tests use fakes. */
interface EntryGesturePorts {
    /** Checks the App Password through the gate's unlock, so the Failure Delay applies. The caller keeps [password]. */
    suspend fun checkPassword(password: CharArray): PasswordCheck

    /** Saves [gesture] as the one the camera uses. Returns false when the save failed. */
    suspend fun save(gesture: EntryGesture): Boolean
}

/**
 * Changes the Hidden Entry Gesture to [target]. The owner re-enters the App Password. A wrong password, or one refused during
 * a Failure Delay, changes nothing and shows nothing. Then the owner performs [target] once on the practice surface, and only
 * that success saves it, so the owner cannot lock themselves out with a gesture they never tried. It has no Android types.
 */
class EntryGestureChangeFlow(
    private val target: EntryGesture,
    private val ports: EntryGesturePorts,
) {
    private val gate = Mutex()
    private val current = MutableStateFlow<EntryGestureStep>(EntryGestureStep.Password(busy = false))

    /** Where the screen is now. */
    val state: StateFlow<EntryGestureStep> = current.asStateFlow()

    /** The owner typed the App Password. A right one opens the practice. [password] is zeroed before this returns. */
    suspend fun submitPassword(password: CharArray) {
        try {
            gate.withLock {
                if (current.value !is EntryGestureStep.Password) return
                current.value = EntryGestureStep.Password(busy = true)
                current.value = when (ports.checkPassword(password)) {
                    PasswordCheck.Accepted -> EntryGestureStep.Practice(busy = false)
                    PasswordCheck.Rejected -> EntryGestureStep.Password(busy = false)
                    PasswordCheck.Unavailable -> EntryGestureStep.Ended(EntryGestureOutcome.UNAVAILABLE)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** The owner performed [target] on the practice surface. It is saved once. A second call changes nothing. */
    suspend fun practiced() {
        gate.withLock {
            val step = current.value
            if (step !is EntryGestureStep.Practice || step.busy) return
            current.value = EntryGestureStep.Practice(busy = true)
            current.value = EntryGestureStep.Ended(
                if (ports.save(target)) EntryGestureOutcome.SAVED else EntryGestureOutcome.UNAVAILABLE,
            )
        }
    }
}
