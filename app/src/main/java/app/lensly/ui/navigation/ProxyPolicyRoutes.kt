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
import app.lensly.app.SettingsStore
import app.lensly.app.VaultHolder
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.vault.VaultException
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.flow.checkAppPassword
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.settings.ProxyPolicyScreen
import app.lensly.ui.settings.ProxyPolicyStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The proxy policy, in the hidden graph, from Settings. The owner picks how a whitelisted proxy is handled and continues. The App
 * Password is then checked through the vault's own check, so a running Failure Delay refuses the change and a wrong password counts
 * as a failure. The policy is saved only when the password is right, and the owner returns to Settings. A wrong password clears the
 * field and shows nothing, as at the gate.
 */
@Composable
internal fun ProxyPolicyRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    val password = remember { PasswordBuffer() }
    var saved by remember { mutableStateOf<ProxyPolicy?>(null) }
    var selected by remember { mutableStateOf<ProxyPolicy?>(null) }
    var step by remember { mutableStateOf<ProxyPolicyStep>(ProxyPolicyStep.Choose) }
    var length by remember { mutableIntStateOf(0) }
    val shown = selected ?: saved

    LaunchedEffect(Unit) {
        saved = readProxyPolicy(holder)
    }
    DisposableEffect(Unit) {
        onDispose { password.clear() }
    }

    fun backToChoose() {
        password.clear()
        length = 0
        step = ProxyPolicyStep.Choose
    }

    // Back leaves the App Password step only when no check is running, and otherwise returns to the choice.
    BackHandler {
        when (val current = step) {
            ProxyPolicyStep.Choose -> navController.popBackStack()
            is ProxyPolicyStep.Password -> if (!current.busy) backToChoose()
        }
    }

    ProxyPolicyScreen(
        step = step,
        saved = saved,
        selected = selected,
        passwordLength = length,
        onSelect = { choice -> selected = choice },
        onContinue = {
            val choice = shown
            if (choice != null && saved != null && choice != saved) {
                step = ProxyPolicyStep.Password(choice, busy = false)
            }
        },
        onKey = { c ->
            val awaiting = (step as? ProxyPolicyStep.Password)?.busy == false
            if (awaiting && password.append(c)) length = password.length
        },
        onBackspace = {
            if ((step as? ProxyPolicyStep.Password)?.busy == false) {
                password.backspace()
                length = password.length
            }
        },
        onSubmit = submit@{
            val current = step as? ProxyPolicyStep.Password ?: return@submit
            if (current.busy || password.isEmpty()) return@submit
            val entered = password.toCharArray()
            password.clear()
            length = 0
            step = current.copy(busy = true)
            scope.launch {
                when (savePolicy(holder, entered, current.policy)) {
                    PolicyOutcome.Saved -> navController.popBackStack()
                    PolicyOutcome.Rejected -> step = current.copy(busy = false)
                    PolicyOutcome.Unavailable -> navController.openHidden(HiddenRoute.UNAVAILABLE)
                }
            }
        },
        onBack = {
            when (val current = step) {
                ProxyPolicyStep.Choose -> navController.popBackStack()
                is ProxyPolicyStep.Password -> if (!current.busy) backToChoose()
            }
        },
        onLock = { leaveHidden(navController, holder) },
    )
}

/** What a save of the policy found. */
private enum class PolicyOutcome {
    /** The App Password was right, and the policy is saved. */
    Saved,

    /** The App Password was wrong, or a Failure Delay is running. Nothing was saved. */
    Rejected,

    /** The vault cannot be read or written. Nothing was saved. */
    Unavailable,
}

/**
 * Checks the App Password, then saves [policy] in the open vault's settings, as the Bulk Cap is saved. NonCancellable, so a check
 * that has started runs to its result. The vault zeroes [password] on every path.
 */
private suspend fun savePolicy(holder: VaultHolder, password: CharArray, policy: ProxyPolicy): PolicyOutcome =
    withContext(NonCancellable) {
        when (checkAppPassword(holder, password)) {
            PasswordCheck.Rejected -> PolicyOutcome.Rejected
            PasswordCheck.Unavailable -> PolicyOutcome.Unavailable
            PasswordCheck.Accepted -> {
                val session = holder.openSession() ?: return@withContext PolicyOutcome.Unavailable
                try {
                    withContext(Dispatchers.IO) {
                        SettingsStore(session).let { store -> store.save(store.load().copy(proxyPolicy = policy)) }
                    }
                    PolicyOutcome.Saved
                } catch (e: VaultException) {
                    PolicyOutcome.Unavailable
                } catch (e: IllegalStateException) {
                    // The session was closed by a lock while the policy was saved.
                    PolicyOutcome.Unavailable
                }
            }
        }
    }

/** The policy in force, or null when the vault cannot be read. Settings shows it, and the choice starts from it. */
internal suspend fun readProxyPolicy(holder: VaultHolder): ProxyPolicy? = withContext(Dispatchers.IO) {
    val session = holder.openSession() ?: return@withContext null
    try {
        SettingsStore(session).load().proxyPolicy
    } catch (e: IllegalStateException) {
        null
    }
}
