package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.PolicyReason
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.checkAppPassword
import app.lensly.ui.settings.ChangeOutcome
import app.lensly.ui.settings.changeAppPassword
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.ui.firstrun.newPasswordProblem

/** What a Change Password submit found. The page shows the problems on the field they belong to, and leaves the page on the other outcomes. */
sealed interface PasswordChange {
    /** The new password is in place, and the old one no longer opens the vault. */
    data object Changed : PasswordChange

    /** The new password is not acceptable, or its repeat differs. Nothing was checked against the current password. */
    data class NewPassword(val problem: NewPasswordProblem) : PasswordChange

    /** The current password is not the App Password, or a Failure Delay is running. Nothing changed. */
    data object WrongCurrent : PasswordChange

    /** The vault was locked before the change could run. Nothing changed. */
    data object Locked : PasswordChange

    /** The vault cannot be checked or changed. Nothing changed. */
    data object Unavailable : PasswordChange
}

/**
 * Change Password, in the order the page reads: the new password and its repeat first, then the current password through the shared unlock
 * path, so a running Failure Delay applies, and only then the change, which re-wraps the vault key under the new password. A step that fails
 * stops the change, so a wrong current password leaves the old one working, and a refused new one counts no wrong try.
 */
suspend fun changeLumioPassword(holder: VaultHolder, current: String, password: String, repeat: String): PasswordChange {
    newPasswordProblem(password, repeat)?.let { return PasswordChange.NewPassword(it) }
    when (checkAppPassword(holder, current.toCharArray())) {
        PasswordCheck.Accepted -> Unit
        PasswordCheck.Rejected -> return PasswordChange.WrongCurrent
        PasswordCheck.Unavailable -> return PasswordChange.Unavailable
    }
    return when (val outcome = changeAppPassword(holder, password.toCharArray())) {
        ChangeOutcome.Changed -> PasswordChange.Changed
        is ChangeOutcome.Refused ->
            PasswordChange.NewPassword(
                if (outcome.reason == PolicyReason.TOO_SHORT) NewPasswordProblem.TooShort else NewPasswordProblem.TooWeak,
            )
        ChangeOutcome.Locked -> PasswordChange.Locked
        else -> PasswordChange.Unavailable
    }
}
