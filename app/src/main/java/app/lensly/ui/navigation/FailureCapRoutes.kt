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
import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.VaultException
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.eraseWarning
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.settings.FailureCapScreen
import app.lensly.ui.settings.FailureCapStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wrong password lockout, in the hidden graph, from Settings: the longest wait and the erase count. The owner picks both and
 * continues. The App Password is then checked through the vault's own check, so a running delay refuses the change and a wrong
 * password counts as a failure. Both are saved only when the password is right, and the owner returns to Settings. A wrong password,
 * or one refused during a Failure Delay, clears the field and shows nothing, as at the gate. The try that erases the vault leaves
 * the hidden area, as after any wipe.
 */
@Composable
internal fun FailureCapRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    val password = remember { PasswordBuffer() }
    var saved by remember { mutableStateOf<FailureCapChoice?>(null) }
    var selected by remember { mutableStateOf<FailureCapChoice?>(null) }
    var savedErase by remember { mutableStateOf<Int?>(null) }
    var selectedErase by remember { mutableStateOf<Int?>(null) }
    var step by remember { mutableStateOf<FailureCapStep>(FailureCapStep.Choose) }
    var length by remember { mutableIntStateOf(0) }
    val shown = selected ?: saved
    val shownErase = selectedErase ?: savedErase
    var triesLeft by remember { mutableStateOf<Int?>(null) }
    val awaitingPassword = (step as? FailureCapStep.Password)?.busy == false

    LaunchedEffect(Unit) {
        saved = readFailureCap(holder)
        savedErase = readEraseAfter(holder)
    }
    // The tries left are read each time the App Password is asked, so the warning counts the wrong tries just made.
    LaunchedEffect(awaitingPassword) { if (awaitingPassword) triesLeft = holder.triesBeforeErase() }
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
        savedErase = savedErase,
        selectedErase = selectedErase,
        passwordLength = length,
        onSelect = { choice -> selected = choice },
        onSelectErase = { erase -> selectedErase = erase },
        onContinue = {
            val choice = shown
            val erase = shownErase
            if (choice != null && erase != null && saved != null && savedErase != null && (choice != saved || erase != savedErase)) {
                step = FailureCapStep.Password(choice, erase, busy = false)
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
                when (saveFailureLimits(holder, entered, current.choice.capMs, current.eraseAfter)) {
                    FailureCapOutcome.Saved -> navController.popBackStack()
                    FailureCapOutcome.Rejected -> step = current.copy(busy = false)
                    FailureCapOutcome.Erased -> leaveHidden(navController, holder)
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
        warning = triesLeft?.let(::eraseWarning),
    )
}

/** What a save of the limits found. */
internal enum class FailureCapOutcome {
    /** The App Password was right, and the limits are saved. */
    Saved,

    /** The App Password was wrong, or a Failure Delay is running. Nothing was saved. */
    Rejected,

    /** The wrong try was the last one allowed, so the vault was erased. */
    Erased,

    /** The vault cannot be read or written. Nothing was saved. */
    Unavailable,
}

/**
 * Checks the App Password and saves the cap and the erase count in one vault call. NonCancellable, so a check that has started runs
 * to its result, and the vault zeroes [password] on every path. A decoy's change is never written (see Vault). It is kept in
 * [VaultHolder.decoyShown] instead, in the decoy's own vault record, so its screens show it as a real vault's show a saved change.
 */
internal suspend fun saveFailureLimits(holder: VaultHolder, password: CharArray, capMs: Long?, eraseAfter: Int): FailureCapOutcome =
    withContext(NonCancellable) {
        try {
            val inDecoy = holder.openSession()?.isDecoy == true
            when (holder.vault.setFailureLimits(password, capMs, eraseAfter, inDecoy = inDecoy)) {
                FailureCapResult.Saved ->
                    if (!inDecoy || withContext(Dispatchers.IO) { holder.decoyShown.showFailureLimits(capMs, eraseAfter) }) {
                        FailureCapOutcome.Saved
                    } else {
                        FailureCapOutcome.Unavailable
                    }
                FailureCapResult.Rejected -> FailureCapOutcome.Rejected
                FailureCapResult.Erased -> FailureCapOutcome.Erased
            }
        } catch (e: VaultException) {
            FailureCapOutcome.Unavailable
        }
    }

/**
 * The cap in force as a choice. A decoy shows its own (see [VaultHolder.decoyShown]) and never reads the real vault's. A vault that
 * cannot be read shows Default, and nothing is saved from that reading.
 */
internal suspend fun readFailureCap(holder: VaultHolder): FailureCapChoice =
    if (holder.openSession()?.isDecoy == true) {
        FailureCapChoice.of(holder.decoyShown.failureCapMs)
    } else {
        try {
            FailureCapChoice.of(holder.vault.failureDelayCapMs())
        } catch (e: VaultException) {
            FailureCapChoice.DEFAULT
        }
    }

/** The erase count in force, read as [readFailureCap] reads the cap. A vault that cannot be read shows the default. */
internal suspend fun readEraseAfter(holder: VaultHolder): Int =
    if (holder.openSession()?.isDecoy == true) {
        holder.decoyShown.failureEraseAfter
    } else {
        try {
            holder.vault.failureEraseAfter()
        } catch (e: VaultException) {
            FailureDelay.DEFAULT_WIPE_AFTER
        }
    }
