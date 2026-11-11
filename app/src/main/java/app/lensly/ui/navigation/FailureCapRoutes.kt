package app.lensly.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.FailureCapResult
import app.lensly.core.vault.VaultException
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.settings.FailureCapScreen
import app.lensly.ui.settings.FailureCapStep
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Failure Delay cap, in the hidden graph, from Settings. The owner picks a cap, reads the warning if it is below 10 s,
 * and continues. The App Password is then checked through the vault's own check, so a running delay refuses the change and
 * a wrong password counts as a failure. The cap is saved only when the password is right, and the owner returns to Settings.
 * A wrong password, or one refused during a Failure Delay, clears the field and shows nothing, as at the gate.
 */
@Composable
internal fun FailureCapRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    val password = remember { PasswordBuffer() }
    var saved by remember { mutableStateOf<FailureCapChoice?>(null) }
    var selected by remember { mutableStateOf<FailureCapChoice?>(null) }
    var step by remember { mutableStateOf<FailureCapStep>(FailureCapStep.Choose) }
    var length by remember { mutableIntStateOf(0) }
    val shown = selected ?: saved

    LaunchedEffect(Unit) {
        saved = readFailureCap(holder)
    }
    DisposableEffect(Unit) {
        onDispose { password.clear() }
    }

    fun backToChoose() {
        password.clear()
        length = 0
        step = FailureCapStep.Choose
    }

    // Back leaves the App Password step only when no check is running, and otherwise returns to the choice.
    BackHandler {
        when (val current = step) {
            FailureCapStep.Choose -> navController.popBackStack()
            is FailureCapStep.Password -> if (!current.busy) backToChoose()
        }
    }

    FailureCapScreen(
        step = step,
        saved = saved,
        selected = selected,
        passwordLength = length,
        onSelect = { choice -> selected = choice },
        onContinue = {
            val choice = shown
            if (choice != null && saved != null && choice != saved) {
                step = FailureCapStep.Password(choice, busy = false)
            }
        },
        onKey = { c ->
            val awaiting = (step as? FailureCapStep.Password)?.busy == false
            if (awaiting && password.append(c)) length = password.length
        },
        onBackspace = {
            if ((step as? FailureCapStep.Password)?.busy == false) {
                password.backspace()
                length = password.length
            }
        },
        onSubmit = submit@{
            val current = step as? FailureCapStep.Password ?: return@submit
            if (current.busy || password.isEmpty()) return@submit
            val entered = password.toCharArray()
            password.clear()
            length = 0
            step = current.copy(busy = true)
            scope.launch {
                when (saveFailureCap(holder, entered, current.choice.capMs)) {
                    FailureCapOutcome.Saved -> navController.popBackStack()
                    FailureCapOutcome.Rejected -> step = current.copy(busy = false)
                    FailureCapOutcome.Unavailable -> navController.openHidden(HiddenRoute.UNAVAILABLE)
                }
            }
        },
        onBack = {
            when (val current = step) {
                FailureCapStep.Choose -> navController.popBackStack()
                is FailureCapStep.Password -> if (!current.busy) backToChoose()
            }
        },
        onLock = { leaveHidden(navController, holder) },
    )
}

/** What a save of the cap found. */
private enum class FailureCapOutcome {
    /** The App Password was right, and the cap is saved. */
    Saved,

    /** The App Password was wrong, or a Failure Delay is running. Nothing was saved. */
    Rejected,

    /** The vault cannot be read or written. Nothing was saved. */
    Unavailable,
}

/**
 * Checks the App Password and saves the cap in one vault call. NonCancellable, so a check that has started runs to its
 * result, and the vault zeroes [password] on every path.
 */
private suspend fun saveFailureCap(holder: VaultHolder, password: CharArray, capMs: Long?): FailureCapOutcome =
    withContext(NonCancellable) {
        try {
            when (holder.vault.setFailureDelayCap(password, capMs)) {
                FailureCapResult.Saved -> FailureCapOutcome.Saved
                FailureCapResult.Rejected -> FailureCapOutcome.Rejected
            }
        } catch (e: VaultException) {
            FailureCapOutcome.Unavailable
        }
    }

/** The cap in force as a choice. A vault that cannot be read shows Default, and nothing is saved from that reading. */
internal suspend fun readFailureCap(holder: VaultHolder): FailureCapChoice =
    try {
        FailureCapChoice.of(holder.vault.failureDelayCapMs())
    } catch (e: VaultException) {
        FailureCapChoice.DEFAULT
    }
