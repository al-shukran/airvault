package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Checks the App Password through the vault's unlock, so the Failure Delay applies. The unlock opens a session only to prove
 * the password, and that session is closed at once. The open session is left alone. NonCancellable, so a session that was
 * opened is always closed, even if the screen left during the check. [password] is zeroed by the vault.
 *
 * A password is accepted only when it opens the same vault as the open session. In a decoy, the password is the Duress
 * Password, and the real App Password is refused, so the check gives nothing away about the real vault.
 */
internal suspend fun checkAppPassword(holder: VaultHolder, password: CharArray): PasswordCheck =
    withContext(NonCancellable) {
        val inDecoy = holder.openSession()?.isDecoy == true
        try {
            when (val result = holder.vault.unlock(password)) {
                is UnlockResult.Unlocked -> {
                    val sameVault = result.session.isDecoy == inDecoy
                    result.session.close()
                    if (sameVault) PasswordCheck.Accepted else PasswordCheck.Rejected
                }
                UnlockResult.Rejected -> PasswordCheck.Rejected
            }
        } catch (e: VaultException) {
            PasswordCheck.Unavailable
        }
    }
