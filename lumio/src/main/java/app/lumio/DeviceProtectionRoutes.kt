package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.BiometricUnlock
import app.lensly.core.vault.VaultSession
import app.lensly.ui.flow.BiometricEnableAppPorts
import app.lensly.ui.flow.BiometricEnableFlow
import app.lensly.ui.flow.BiometricEnableOutcome
import app.lensly.ui.flow.BiometricEnableStep
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.wallet.PasswordStep
import app.lumio.ui.kit.lumioComposable
import kotlinx.coroutines.launch

/**
 * Device Protection: the App Password step that turning the Biometric Unlock switch of Settings → Security on asks for first. Turning it off needs no
 * password: it deletes the sealed Vault Key and the biometric key. In a Duress Password session the switch reads off and neither change is made, as
 * turning it on there seals nothing.
 */
internal fun NavGraphBuilder.deviceProtectionRoutes(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection) {
    lumioComposable(LumioRoute.DEVICE_PROTECTION_ENABLE) {
        LumioBackdrop(selection) { DeviceProtectionEnableRoute(navController = navController, holder = holder) }
    }
}

/**
 * Whether turning the switch on opens the App Password step. A Decoy Vault seals no Biometric Unlock, so there turning it on does nothing,
 * as turning it off does nothing. The step would take the real password on screen, and an observer could learn that a real vault exists.
 */
internal fun turningOnOpensPasswordStep(session: VaultSession): Boolean = !session.isDecoy

/** The App Password step of turning Biometric Unlock on. Either outcome returns to the page, which reads the switch again. */
@Composable
private fun DeviceProtectionEnableRoute(navController: NavHostController, holder: VaultHolder) {
    if (!rememberGateSession(navController, holder)) return
    DeviceProtectionEnableScreen(
        holder = holder,
        biometric = (LocalContext.current.applicationContext as LumioApp).biometricUnlock,
        onDone = { navController.popBackStack() },
        onUnavailable = { navController.openHidden(GateRoutes.UNAVAILABLE) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The App Password, checked through the shared flow. A right password runs the biometric prompt, which Lumio draws nothing over. A wrong one
 * shows nothing, as at the gate.
 */
@Composable
private fun DeviceProtectionEnableScreen(
    holder: VaultHolder,
    biometric: BiometricUnlock,
    onDone: () -> Unit,
    onUnavailable: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val flow = remember { BiometricEnableFlow(DeviceProtectionPorts(BiometricEnableAppPorts(holder, biometric), holder)) }
    val password = remember { PasswordBuffer() }
    val step by flow.state.collectAsState()
    var length by remember { mutableIntStateOf(0) }
    val awaitingPassword = (step as? BiometricEnableStep.Password)?.busy == false

    DisposableEffect(flow) {
        onDispose { password.clear() }
    }
    LaunchedEffect(step) {
        when ((step as? BiometricEnableStep.Ended)?.outcome) {
            BiometricEnableOutcome.ON, BiometricEnableOutcome.OFF -> onDone()
            BiometricEnableOutcome.UNAVAILABLE -> onUnavailable()
            null -> Unit
        }
    }

    val current = step
    if (current is BiometricEnableStep.Password) {
        PasswordStep(
            busy = current.busy,
            length = length,
            onKey = { c ->
                if (awaitingPassword && password.append(c)) length = password.length
            },
            onBackspace = {
                if (awaitingPassword) {
                    password.backspace()
                    length = password.length
                }
            },
            onSubmit = submit@{
                if (!awaitingPassword || password.isEmpty()) return@submit
                val entered = password.toCharArray()
                password.clear()
                length = 0
                scope.launch { flow.submitPassword(entered) }
            },
            onBack = onBack,
            modifier = Modifier,
            title = "Device Protection",
            prompt = "Enter your Application Password to turn on Biometric Unlock.",
            stepCount = 2,
        )
    }
}
