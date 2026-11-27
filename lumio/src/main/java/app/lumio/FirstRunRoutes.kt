package app.lumio

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.flow.BiometricEnableAppPorts
import app.lensly.ui.navigation.GateOutcome
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.HiddenBackdrop
import app.lensly.ui.navigation.MirrorNavMotion
import app.lensly.ui.navigation.createVault
import app.lensly.ui.navigation.leaveHidden
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.ui.firstrun.DeviceProtectionScreen
import app.lumio.ui.firstrun.DuressScreen
import app.lumio.ui.firstrun.FirstRunIntro
import app.lumio.ui.firstrun.FirstRunPasswordCopy
import app.lumio.ui.firstrun.FirstRunPasswordScreen
import app.lumio.ui.firstrun.FirstRunStep
import app.lumio.ui.firstrun.NewPasswordProblem
import app.lumio.ui.firstrun.NoticeScreen
import app.lumio.ui.firstrun.PrepareScreen
import app.lumio.ui.firstrun.newPasswordProblem
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.pair.PairChrome
import kotlinx.coroutines.launch

/**
 * Lumio's first-run screens, I1 to I10, inside the hidden area. They run before the vault exists: the App Password step creates it, and the
 * duress, device and pair steps follow. [home] is where the owner lands when the pair step is left. Lensly's hidden area does not register these.
 */
internal fun NavGraphBuilder.firstRunRoutes(navController: NavHostController, holder: VaultHolder, draft: PairDraft, home: String) {
    firstRunScreen(FirstRunStep.Welcome) {
        FirstRunIntro(
            onLeave = { leaveHidden(navController, holder) },
            onNotice = { navController.navigate(FirstRunStep.Notice.route) },
        )
    }
    firstRunScreen(FirstRunStep.Notice) {
        var agreed by remember { mutableStateOf(false) }
        NoticeScreen(
            agreed = agreed,
            onAgreedChange = { agreed = it },
            onContinue = { navController.navigate(FirstRunStep.PrepareLensly.route) },
        )
    }
    firstRunScreen(FirstRunStep.PrepareLensly) {
        PrepareScreen(onNext = { navController.navigate(FirstRunStep.CreatePassword.route) })
    }
    firstRunScreen(FirstRunStep.CreatePassword) {
        val scope = rememberCoroutineScope()
        var password by remember { mutableStateOf(DebugDemoData.text("new")) }
        var repeat by remember { mutableStateOf(DebugDemoData.text("repeat")) }
        var shown by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<NewPasswordProblem?>(null) }
        var busy by remember { mutableStateOf(false) }
        // Leaving while the vault is made would cancel the step half way, with a vault on disk and no session.
        BackHandler(enabled = busy) {}
        FirstRunPasswordScreen(
            password = password,
            repeat = repeat,
            shown = shown,
            problem = problem,
            busy = busy,
            onPassword = {
                password = it
                problem = null
            },
            onRepeat = {
                repeat = it
                problem = null
            },
            onToggleShown = { shown = !shown },
            onNext = submit@{
                if (busy) return@submit
                problem = newPasswordProblem(password, repeat)
                if (problem != null) return@submit
                busy = true
                scope.launch {
                    val outcome = createFirstVault(holder, password.toCharArray())
                    busy = false
                    when (outcome) {
                        // A vault is already on disk, so the gate decides where the owner goes.
                        null -> navController.openHidden(GateRoutes.GATE)
                        GateOutcome.Opened -> navController.navigate(FirstRunStep.Duress.route) {
                            popUpTo(FirstRunStep.Welcome.route) { inclusive = true }
                        }
                        // Nothing was created, so the owner types the password again.
                        GateOutcome.Cancelled -> {
                            password = ""
                            repeat = ""
                        }
                        GateOutcome.Erased -> leaveHidden(navController, holder)
                        else -> navController.openHidden(GateRoutes.UNAVAILABLE)
                    }
                }
            },
        )
    }
    firstRunScreen(FirstRunStep.Duress) {
        BackHandler { leaveHidden(navController, holder) }
        DuressScreen(
            onSetUp = { navController.navigate(FirstRunStep.DuressPassword.route) },
            onMaybeLater = { navController.navigate(FirstRunStep.DeviceProtection.route) },
        )
    }
    firstRunScreen(FirstRunStep.DuressPassword) { DuressPasswordStep(navController, holder) }
    firstRunScreen(FirstRunStep.DeviceProtection) {
        BackHandler { leaveHidden(navController, holder) }
        DeviceProtectionStep(holder = holder, onDone = { openPairStep(navController, home) })
    }
    // The scan of Lensly's code is the last step. System back leaves it for Home, so an owner with no Lensly at hand is never held here.
    firstRunScreen(FirstRunStep.Pair) {
        PairScanRoute(navController = navController, draft = draft, route = FirstRunStep.Pair.route, chrome = PairChrome.Onboarding)
    }
}

/** Opens [home] and the pair step above it, so that leaving the pair step, with back or a cancelled review, lands on [home]. */
internal fun openPairStep(navController: NavHostController, home: String) {
    navController.openHidden(home)
    navController.navigate(FirstRunStep.Pair.route)
}

/**
 * The Duress Password page that Set Up Now opens. The owner has just made the App Password, so the page asks for no current password. A password
 * that is set goes on to I9, and Back returns to the question. A password that opens the real vault stays on the page with its words.
 */
@Composable
private fun DuressPasswordStep(navController: NavHostController, holder: VaultHolder) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<NewPasswordProblem?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Leaving while the set runs would leave the owner not knowing whether the password took.
    BackHandler(enabled = busy) {}
    FirstRunPasswordScreen(
        password = password,
        repeat = repeat,
        shown = shown,
        problem = problem,
        busy = busy,
        onPassword = {
            password = it
            problem = null
        },
        onRepeat = {
            repeat = it
            problem = null
        },
        onToggleShown = { shown = !shown },
        copy = FirstRunPasswordCopy.Duress,
        onNext = submit@{
            if (busy) return@submit
            busy = true
            scope.launch {
                val outcome = setLumioDuressPassword(holder, current = null, password = password, repeat = repeat)
                busy = false
                when (outcome) {
                    PasswordChange.Changed -> navController.navigate(FirstRunStep.DeviceProtection.route)
                    is PasswordChange.NewPassword -> problem = outcome.problem
                    PasswordChange.Locked -> navController.openHidden(GateRoutes.GATE)
                    else -> navController.openHidden(GateRoutes.UNAVAILABLE)
                }
            }
        },
    )
}

/**
 * Creates the vault as the shared App Password screen does, or returns null when a vault, or what is left of one, is already on disk. An
 * earlier try may have made it: a rotation recreates this screen while the first try still runs. The vault is never made twice.
 */
internal suspend fun createFirstVault(holder: VaultHolder, password: CharArray): GateOutcome? = try {
    createVault(holder, password)
} catch (e: IllegalStateException) {
    null
}

/**
 * I9 with its prompt: Enable turns biometric unlock on, which seals the vault key under the owner's biometric. A failure or a cancel stays on
 * the page with no error. Not Now goes on. Without a strong biometric on the phone, Enable is off.
 */
@Composable
private fun DeviceProtectionStep(holder: VaultHolder, onDone: () -> Unit) {
    val biometric = (LocalContext.current.applicationContext as LumioApp).biometricUnlock
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val available = remember { biometric.canEnable() }
    val ports = remember { DeviceProtectionPorts(BiometricEnableAppPorts(holder, biometric), holder) }
    DeviceProtectionScreen(
        available = available,
        onEnable = submit@{
            val session = holder.openSession()
            if (asking || !available || session == null) return@submit
            asking = true
            scope.launch {
                val enabled = ports.enable()
                asking = false
                if (enabled) onDone()
            }
        },
        onNotNow = onDone,
    )
}

private fun NavGraphBuilder.firstRunScreen(step: FirstRunStep, content: @Composable () -> Unit) {
    lumioComposable(step.route) { FirstRunBackdrop(content) }
}

/** The Clear Air look over the hidden backdrop. The first-run screens are Lumio's alone, so they take the Lumio theme here, not from the shared gate. */
@Composable
internal fun FirstRunBackdrop(content: @Composable () -> Unit) {
    LumenTheme {
        ClearAirTheme {
            MirrorNavMotion()
            HiddenBackdrop(content = content)
        }
    }
}
