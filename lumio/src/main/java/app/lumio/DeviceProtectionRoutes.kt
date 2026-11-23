package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lensly.app.VaultHolder
import app.lensly.core.vault.BiometricUnlock
import app.lensly.ui.flow.BiometricEnableAppPorts
import app.lensly.ui.flow.BiometricEnableFlow
import app.lensly.ui.flow.BiometricEnableOutcome
import app.lensly.ui.flow.BiometricEnableStep
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.wallet.PasswordStep
import app.lumio.ui.kit.lumioComposable
import app.lumio.ui.settings.BiometricSwitch
import app.lumio.ui.settings.DeviceProtectionSettingsPage
import kotlinx.coroutines.launch

/**
 * Device Protection: the page under Settings → Security that holds the Biometric Unlock switch, and the App Password step that turning the
 * switch on asks for first. Turning it off needs no password: it deletes the sealed Vault Key and the biometric key. In a Duress Password
 * session the switch reads off and neither change is made, as turning it on there seals nothing.
 */
internal fun NavGraphBuilder.deviceProtectionRoutes(navController: NavHostController, holder: VaultHolder, selection: NetworkSelection) {
    lumioComposable(LumioRoute.SETTINGS_DEVICE_PROTECTION) {
        LumioBackdrop(selection) { DeviceProtectionRoute(navController = navController, holder = holder) }
    }
    lumioComposable(LumioRoute.DEVICE_PROTECTION_ENABLE) {
        LumioBackdrop(selection) { DeviceProtectionEnableRoute(navController = navController, holder = holder) }
    }
}

/** The switch. It is read from the phone again each time the page comes to the top, so a change made on the password step shows here. */
@Composable
private fun DeviceProtectionRoute(navController: NavHostController, holder: VaultHolder) {
    if (!rememberGateSession(navController, holder)) return
    val session = holder.openSession() ?: return
    val biometric = (LocalContext.current.applicationContext as LumioApp).biometricUnlock
    val top by navController.currentBackStackEntryAsState()
    val scope = rememberCoroutineScope()
    val canEnable = remember { biometric.canEnable() }
    var on by remember(top) { mutableStateOf(biometric.isOn(session)) }
    DeviceProtectionSettingsPage(
        biometric = BiometricSwitch(on = on, canEnable = canEnable),
        onBiometric = { want ->
            if (want) {
                navController.navigate(LumioRoute.DEVICE_PROTECTION_ENABLE)
            } else {
                scope.launch {
                    biometric.disable(session)
                    on = biometric.isOn(session)
                }
            }
        },
        onBack = { navController.popBackStack() },
    )
}

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
