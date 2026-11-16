package app.lensly.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.PasswordPolicy
import app.lensly.core.vault.PolicyReason
import app.lensly.core.vault.PolicyResult
import app.lensly.core.vault.VaultException
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.checkAppPassword
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.navigation.toPolicyError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a step of a password change found. Each app shows its own screen for the outcomes it leaves to the caller. */
sealed interface ChangeOutcome {
    /** The current password opened the vault. The session that proved it has been closed again. */
    data object Verified : ChangeOutcome

    /** The current password was wrong, or a Failure Delay is running. */
    data object Rejected : ChangeOutcome

    /** The new password is in place, and the old one no longer opens the vault. */
    data object Changed : ChangeOutcome

    /** The policy refused the new password. Nothing changed. */
    data class Refused(val reason: PolicyReason) : ChangeOutcome

    /** The Duress Password is the App Password. Nothing changed. */
    data object SameAsApp : ChangeOutcome

    /** The vault was locked before the change could run. Nothing changed. */
    data object Locked : ChangeOutcome

    /** The vault cannot be read or re-wrapped. Nothing changed. */
    data object Unavailable : ChangeOutcome
}

/**
 * Change the App Password of the open vault: the vault re-wraps its key under [password], with the key of the open session. NonCancellable,
 * so a change that has started runs to its commit or to its failure. No record is re-encrypted, and an interrupted change leaves one
 * password working. The vault zeroes [password] on every path.
 */
suspend fun changeAppPassword(holder: VaultHolder, password: CharArray): ChangeOutcome =
    withContext(NonCancellable) {
        val session = holder.openSession()
        if (session == null) {
            password.fill('\u0000')
            return@withContext ChangeOutcome.Locked
        }
        try {
            when (val result = holder.vault.rekey(session, password)) {
                CreateResult.Created -> ChangeOutcome.Changed
                is CreateResult.PolicyRejected -> ChangeOutcome.Refused(result.reason)
                CreateResult.DeviceUnsupported, CreateResult.BackupAllowed -> ChangeOutcome.Unavailable
            }
        } catch (e: VaultException) {
            ChangeOutcome.Unavailable
        } catch (e: IllegalStateException) {
            // The session was closed by a lock while the change ran.
            ChangeOutcome.Locked
        }
    }

/**
 * Change a password of the open vault in three steps: the current password, then the new one twice. The current password goes through the
 * vault's unlock, so the Failure Delay applies, and a wrong one clears the field and shows nothing, as the gate does. The new password must
 * pass [PasswordPolicy]. [change] makes the change once the new password is confirmed: [changeAppPassword] for the App Password, or the app's
 * own change such as the Duress Password. [onLeave] is Back and Done. [onLocked] runs when the vault was locked before the change could run,
 * and [onUnavailable] when the vault cannot be checked or changed. The app decides where each of them goes.
 */
@Composable
fun ChangePasswordFlow(
    holder: VaultHolder,
    copy: ChangeCopy,
    change: suspend (CharArray) -> ChangeOutcome,
    onLeave: () -> Unit,
    onLocked: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val buffer = remember { PasswordBuffer() }
    var step by remember { mutableStateOf(ChangeStep.Current) }
    var length by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<PolicyError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    // The first new password, held only between the New and Confirm steps.
    var first by remember { mutableStateOf<CharArray?>(null) }

    BackHandler(enabled = !busy) { onLeave() }
    DisposableEffect(Unit) {
        onDispose {
            buffer.clear()
            first?.fill('\u0000')
        }
    }

    fun clearEntry() {
        buffer.clear()
        length = 0
    }

    ChangePasswordScreen(
        state = ChangePasswordState(step = step, length = length, error = error, busy = busy, changed = changed),
        onKey = { c ->
            if (!busy && !changed && buffer.append(c)) {
                length = buffer.length
                error = null
            }
        },
        onBackspace = {
            if (!busy && !changed) {
                buffer.backspace()
                length = buffer.length
                error = null
            }
        },
        onSubmit = submit@{
            if (busy || changed || buffer.isEmpty()) return@submit
            when (step) {
                ChangeStep.Current -> {
                    val current = buffer.toCharArray()
                    clearEntry()
                    busy = true
                    scope.launch {
                        val outcome = verifyCurrent(holder, current)
                        busy = false
                        when (outcome) {
                            ChangeOutcome.Verified -> {
                                step = ChangeStep.New
                            }
                            // A wrong password, or a running delay, is silent, as it is at the gate.
                            ChangeOutcome.Rejected -> Unit
                            else -> onUnavailable()
                        }
                    }
                }
                ChangeStep.New -> {
                    val entered = buffer.toCharArray()
                    clearEntry()
                    when (val policy = PasswordPolicy.check(entered)) {
                        is PolicyResult.Refused -> {
                            entered.fill('\u0000')
                            error = policy.reason.toPolicyError()
                        }
                        PolicyResult.Accepted -> {
                            first?.fill('\u0000')
                            first = entered
                            step = ChangeStep.Confirm
                        }
                    }
                }
                ChangeStep.Confirm -> {
                    val second = buffer.toCharArray()
                    val expected = first
                    val matches = expected != null && expected.contentEquals(second)
                    second.fill('\u0000')
                    clearEntry()
                    if (expected == null || !matches) {
                        error = PolicyError.Mismatch
                    } else {
                        // The vault zeroes the array it is given, so the change gets a copy.
                        val next = expected.copyOf()
                        expected.fill('\u0000')
                        first = null
                        busy = true
                        scope.launch {
                            val outcome = change(next)
                            busy = false
                            when (outcome) {
                                ChangeOutcome.Changed -> {
                                    changed = true
                                }
                                is ChangeOutcome.Refused -> {
                                    step = ChangeStep.New
                                    error = outcome.reason.toPolicyError()
                                }
                                ChangeOutcome.SameAsApp -> {
                                    step = ChangeStep.New
                                    error = PolicyError.SameAsApp
                                }
                                ChangeOutcome.Locked -> onLocked()
                                else -> onUnavailable()
                            }
                        }
                    }
                }
            }
        },
        onBack = onLeave,
        onDone = onLeave,
        copy = copy,
    )
}

/**
 * Checks the current password through the gate's unlock path. The session it opens only proves the password, so it is
 * closed at once. NonCancellable, so an opened session is always closed, even if the screen left while it was checked.
 */
private suspend fun verifyCurrent(holder: VaultHolder, password: CharArray): ChangeOutcome =
    when (checkAppPassword(holder, password)) {
        PasswordCheck.Accepted -> ChangeOutcome.Verified
        PasswordCheck.Rejected -> ChangeOutcome.Rejected
        PasswordCheck.Unavailable -> ChangeOutcome.Unavailable
    }
