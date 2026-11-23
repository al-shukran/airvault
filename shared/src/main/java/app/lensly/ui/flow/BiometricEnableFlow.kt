package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.core.vault.BiometricUnlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How turning Biometric Unlock on ended. */
enum class BiometricEnableOutcome {
    /** The owner's biometric sealed the Vault Key, and biometric unlock is on. */
    ON,

    /** The owner cancelled the biometric prompt, or the seal failed. Nothing is on, and the password is still the way in. */
    OFF,

    /** The vault cannot be checked. Nothing changed. */
    UNAVAILABLE,
}

/** Where turning Biometric Unlock on is: the App Password, then the biometric prompt, then the outcome. */
sealed interface BiometricEnableStep {
    /** The App Password is asked. [busy] while the check and the biometric prompt run. A wrong password returns here with nothing shown. */
    data class Password(val busy: Boolean) : BiometricEnableStep

    /** Turning on has ended, and [outcome] says how. */
    data class Ended(val outcome: BiometricEnableOutcome) : BiometricEnableStep
}

/** What [BiometricEnableFlow] asks of the app. [BiometricEnableAppPorts] holds the app's implementation, and tests use fakes. */
interface BiometricEnablePorts {
    /** Checks the App Password through the vault's unlock, so the Failure Delay applies. The caller keeps [password]. */
    suspend fun checkPassword(password: CharArray): PasswordCheck

    /** Seals the open vault's Vault Key under the owner's biometric. False when the owner does not pass, or the seal fails. */
    suspend fun enable(): Boolean
}

/**
 * Turns Biometric Unlock on. The owner re-enters the App Password, and only a right one reaches the biometric prompt that seals the Vault
 * Key. A wrong password, or one refused during a Failure Delay, changes nothing and shows nothing. It has no Android types.
 */
class BiometricEnableFlow(private val ports: BiometricEnablePorts) {
    private val gate = Mutex()
    private val current = MutableStateFlow<BiometricEnableStep>(BiometricEnableStep.Password(busy = false))

    /** Where the screen is now. */
    val state: StateFlow<BiometricEnableStep> = current.asStateFlow()

    /** The owner typed the App Password. A right one runs the biometric prompt. [password] is zeroed before this returns. */
    suspend fun submitPassword(password: CharArray) {
        try {
            gate.withLock {
                if (current.value !is BiometricEnableStep.Password) return
                current.value = BiometricEnableStep.Password(busy = true)
                current.value = when (ports.checkPassword(password)) {
                    PasswordCheck.Accepted -> BiometricEnableStep.Ended(
                        if (ports.enable()) BiometricEnableOutcome.ON else BiometricEnableOutcome.OFF,
                    )
                    PasswordCheck.Rejected -> BiometricEnableStep.Password(busy = false)
                    PasswordCheck.Unavailable -> BiometricEnableStep.Ended(BiometricEnableOutcome.UNAVAILABLE)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
}

/**
 * The app's [BiometricEnablePorts]: the App Password through [checkAppPassword], and the seal of the session that is open. Turning on needs
 * an open session, and [BiometricUnlock.enable] refuses a Duress Password session.
 */
class BiometricEnableAppPorts(private val holder: VaultHolder, private val biometric: BiometricUnlock) : BiometricEnablePorts {
    override suspend fun checkPassword(password: CharArray): PasswordCheck = checkAppPassword(holder, password)

    override suspend fun enable(): Boolean {
        val session = holder.openSession() ?: return false
        return biometric.enable(session)
    }
}
