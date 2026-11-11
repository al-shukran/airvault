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
 */
internal suspend fun checkAppPassword(holder: VaultHolder, password: CharArray): PasswordCheck =
    withContext(NonCancellable) {
        try {
            when (val result = holder.vault.unlock(password)) {
                is UnlockResult.Unlocked -> {
                    result.session.close()
                    PasswordCheck.Accepted
                }
                UnlockResult.Rejected -> PasswordCheck.Rejected
            }
        } catch (e: VaultException) {
            PasswordCheck.Unavailable
        }
    }
