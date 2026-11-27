package app.lensly.ui.flow

import app.lensly.app.VaultHolder
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Checks the App Password through the vault's verify, so the Failure Delay applies. The check opens a session only to prove
 * the password, and that session is closed at once. The open session is left alone. NonCancellable, so a session that was
 * opened is always closed, even if the screen left during the check. A caller that was cancelled meanwhile gets the cancellation
 * instead of the result, so a cancelled step never applies its change. [password] is zeroed by the vault.
 *
 * The Duress Password is a wrong password here: the check never promotes the Decoy Vault, which only an unlock does.
 */
suspend fun checkAppPassword(holder: VaultHolder, password: CharArray): PasswordCheck =
    withContext(NonCancellable) {
        try {
            when (val result = holder.vault.verify(password)) {
                is UnlockResult.Unlocked -> {
                    result.session.close()
                    PasswordCheck.Accepted
                }
                UnlockResult.Rejected -> PasswordCheck.Rejected
                // The last allowed try erased the vault, so the open session goes with it, as on a wipe.
                UnlockResult.Erased -> {
                    holder.lock()
                    PasswordCheck.Rejected
                }
            }
        } catch (e: VaultException) {
            PasswordCheck.Unavailable
        }
    }.also { currentCoroutineContext().ensureActive() }
