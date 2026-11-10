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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lensly.BuildConfig
import app.lensly.app.AppSettings
import app.lensly.app.RegistryHolder
import app.lensly.app.SettingsStore
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.PasswordPolicy
import app.lensly.core.vault.PolicyReason
import app.lensly.core.vault.PolicyResult
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.VaultException
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.settings.CatalogCounts
import app.lensly.ui.settings.ChangePasswordScreen
import app.lensly.ui.settings.ChangePasswordState
import app.lensly.ui.settings.ChangeStep
import app.lensly.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Settings screen and the App Password change, in the hidden graph. */
internal fun NavGraphBuilder.settingsRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.SETTINGS) { HiddenBackdrop { SettingsRoute(navController, holder) } }
    composable(HiddenRoute.CHANGE_PASSWORD) { HiddenBackdrop { ChangePasswordRoute(navController, holder) } }
}

/**
 * Settings: the saved Bulk Cap, which a tap on an option saves at once, the catalog counts, and the About notices.
 * Every store read and write runs off the main thread, and a closed session returns the owner to the camera.
 */
@Composable
private fun SettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var catalog by remember { mutableStateOf<CatalogCounts?>(null) }

    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            settings = withContext(Dispatchers.IO) { SettingsStore(session).load() }
        }
    }
    // Read again each time the screen is shown, so a package installed from Update catalog is counted.
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        catalog = withContext(Dispatchers.IO) {
            RegistryHolder.get(context).let { CatalogCounts(networks = it.networks().size, tokens = it.tokens().size) }
        }
    }

    SettingsScreen(
        bulkCap = settings?.bulkCap,
        onBulkCap = { cap ->
            scope.launch {
                whileUnlocked(navController, holder) {
                    val next = AppSettings(bulkCap = cap)
                    withContext(Dispatchers.IO) { SettingsStore(session).save(next) }
                    settings = next
                }
            }
        },
        onChangePassword = { navController.navigate(HiddenRoute.CHANGE_PASSWORD) },
        catalog = catalog,
        onUpdateCatalog = { navController.navigate(HiddenRoute.CATALOG) },
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

/**
 * Change the App Password: the current password, then the new one twice. The current password goes through the
 * gate's unlock, so the Failure Delay applies. The new password must pass [PasswordPolicy], and then the vault
 * re-wraps its key with [app.lensly.core.vault.Vault.rekey]. No record is re-encrypted, and an interrupted change
 * leaves one password working. A wrong current password clears the field and says nothing, as the gate does.
 */
@Composable
private fun ChangePasswordRoute(navController: NavHostController, holder: VaultHolder) {
    val scope = rememberCoroutineScope()
    val buffer = remember { PasswordBuffer() }
    var step by remember { mutableStateOf(ChangeStep.Current) }
    var length by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<PolicyError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    // The first new password, held only between the New and Confirm steps.
    var first by remember { mutableStateOf<CharArray?>(null) }

    BackHandler(enabled = !busy) { navController.popBackStack() }
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
                            else -> navController.openHidden(HiddenRoute.UNAVAILABLE)
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
                            val outcome = changeWith(holder, next)
                            busy = false
                            when (outcome) {
                                ChangeOutcome.Changed -> {
                                    changed = true
                                }
                                is ChangeOutcome.Refused -> {
                                    step = ChangeStep.New
                                    error = outcome.reason.toPolicyError()
                                }
                                ChangeOutcome.Locked -> leaveHidden(navController, holder)
                                else -> navController.openHidden(HiddenRoute.UNAVAILABLE)
                            }
                        }
                    }
                }
            }
        },
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
        onDone = { navController.popBackStack() },
    )
}

/** What a step of the change found. */
private sealed interface ChangeOutcome {
    /** The current password opened the vault. The session that proved it has been closed again. */
    data object Verified : ChangeOutcome

    /** The current password was wrong, or a Failure Delay is running. */
    data object Rejected : ChangeOutcome

    /** The new password is in place, and the old one no longer opens the vault. */
    data object Changed : ChangeOutcome

    /** The policy refused the new password. Nothing changed. */
    data class Refused(val reason: PolicyReason) : ChangeOutcome

    /** The vault was locked before the change could run. Nothing changed. */
    data object Locked : ChangeOutcome

    /** The vault cannot be read or re-wrapped. Nothing changed. */
    data object Unavailable : ChangeOutcome
}

/**
 * Checks the current password through the gate's unlock path. The session it opens only proves the password, so it is
 * closed at once. NonCancellable, so an opened session is always closed, even if the screen left while it was checked.
 */
private suspend fun verifyCurrent(holder: VaultHolder, password: CharArray): ChangeOutcome =
    withContext(NonCancellable) {
        try {
            when (val result = holder.vault.unlock(password)) {
                is UnlockResult.Unlocked -> {
                    result.session.close()
                    ChangeOutcome.Verified
                }
                UnlockResult.Rejected -> ChangeOutcome.Rejected
            }
        } catch (e: VaultException) {
            ChangeOutcome.Unavailable
        }
    }

/**
 * Re-wraps the vault key under [password], with the key of the open session. NonCancellable, so a change that has
 * started runs to its commit or to its failure. The vault zeroes [password] on every path.
 */
private suspend fun changeWith(holder: VaultHolder, password: CharArray): ChangeOutcome =
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
                CreateResult.DeviceUnsupported -> ChangeOutcome.Unavailable
            }
        } catch (e: VaultException) {
            ChangeOutcome.Unavailable
        } catch (e: IllegalStateException) {
            // The session was closed by a lock while the change ran.
            ChangeOutcome.Locked
        }
    }
