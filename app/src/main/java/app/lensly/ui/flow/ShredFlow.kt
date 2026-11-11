package app.lensly.ui.flow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a destructive action ended. */
enum class ShredOutcome {
    /** The target was erased. The screen leaves. */
    ERASED,

    /** The vault was locked before the erase could run. Nothing was erased, and the gate takes over. */
    LOCKED,

    /** The erase could not finish, so nothing is claimed as erased. */
    UNAVAILABLE,
}

/** Where a destructive flow is: the App Password, then the warning with its confirmation, then the outcome. */
sealed interface ShredStep {
    /** The App Password is asked. [busy] while the check runs. A wrong password returns here with nothing shown. */
    data class Password(val busy: Boolean) : ShredStep

    /** The password was right. Nothing is erased until the owner confirms. [busy] while the erase runs. */
    data class Confirm(val busy: Boolean) : ShredStep

    /** The erase has ended, and [outcome] says how. */
    data class Ended(val outcome: ShredOutcome) : ShredStep
}

/** What [ShredFlow] asks of the app. [VaultShredPorts.kt] holds the app's implementations, and tests use fakes. */
interface ShredPorts {
    /** Checks the App Password through the gate's unlock, so the Failure Delay applies. The caller keeps [password]. */
    suspend fun checkPassword(password: CharArray): PasswordCheck

    /** Erases the target once, and says how it ended. */
    suspend fun erase(): ShredOutcome
}

/**
 * A destructive action behind the App Password: Delete Wallet and Wipe. The owner re-enters the App Password, reads a
 * warning, and confirms. Only the confirmation erases anything. A wrong password, or one refused during a Failure Delay,
 * erases nothing and shows nothing. It has no Android types, and [ShredPorts] does the vault work.
 */
class ShredFlow(private val ports: ShredPorts) {
    private val gate = Mutex()
    private val current = MutableStateFlow<ShredStep>(ShredStep.Password(busy = false))

    /** Where the screen is now. */
    val state: StateFlow<ShredStep> = current.asStateFlow()

    /** The owner typed the App Password. A right one opens the warning. [password] is zeroed before this returns. */
    suspend fun submitPassword(password: CharArray) {
        try {
            gate.withLock {
                if (current.value !is ShredStep.Password) return
                current.value = ShredStep.Password(busy = true)
                current.value = when (ports.checkPassword(password)) {
                    PasswordCheck.Accepted -> ShredStep.Confirm(busy = false)
                    PasswordCheck.Rejected -> ShredStep.Password(busy = false)
                    PasswordCheck.Unavailable -> ShredStep.Ended(ShredOutcome.UNAVAILABLE)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** The owner confirmed on the warning. The erase runs once, and a second call changes nothing. */
    suspend fun confirm() {
        gate.withLock {
            if (current.value !is ShredStep.Confirm) return
            current.value = ShredStep.Confirm(busy = true)
            current.value = ShredStep.Ended(ports.erase())
        }
    }
}
