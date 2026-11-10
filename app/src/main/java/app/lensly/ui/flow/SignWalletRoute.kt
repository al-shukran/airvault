package app.lensly.ui.flow

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen

/**
 * SIGN for the wallet [walletId]: scan the request, read the review, approve it with the App Password, then show the
 * signature. Leaving the flow in any way pops back to the wallet.
 */
@Composable
internal fun SignWalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val context = LocalContext.current.applicationContext
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(HiddenRoute.GATE)
    }

    val flow: SignFlowViewModel = viewModel(
        key = walletId,
        factory = viewModelFactory {
            initializer { SignFlowViewModel(SignFlow(VaultSignPorts(context, holder, walletId))) }
        },
    )
    val step by flow.state.collectAsState()
    BackHandler { flow.back() }
    LaunchedEffect(step) {
        if (step == SignStep.Closed) navController.popBackStack()
    }

    when (val shown = step) {
        is SignStep.Scanning -> ScanScreen(
            title = "SIGN REQUEST",
            progress = shown.progress,
            statusText = shown.status,
            onText = flow::onScanned,
            onCancel = flow::leave,
        )
        is SignStep.Refused -> SignRefusedScreen(reason = shown.reason, onDone = flow::leave)
        is SignStep.Review -> TxReviewScreen(
            review = shown.ready.review,
            onApprove = flow::approve,
            onReject = flow::leave,
        )
        is SignStep.Confirming -> ConfirmPasswordRoute(busy = shown.busy, onConfirm = flow::confirm)
        is SignStep.Signature -> ShowQrScreen(
            title = "SIGNATURE",
            subtitle = "Scan this with your online wallet",
            parts = shown.parts,
            onDone = flow::leave,
        )
        SignStep.Closed -> Unit
    }
}

/**
 * The App Password check before a signature. Like the unlock screen, it shows no error: a wrong password clears the field.
 * The typed characters are copied out, and the buffer is cleared, on every submit.
 */
@Composable
private fun ConfirmPasswordRoute(busy: Boolean, onConfirm: (CharArray) -> Unit) {
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { buffer.clear() }
    }

    ConfirmPasswordScreen(
        length = length,
        busy = busy,
        onKey = { c ->
            if (!busy && buffer.append(c)) length = buffer.length
        },
        onBackspace = {
            if (!busy) {
                buffer.backspace()
                length = buffer.length
            }
        },
        onSubmit = submit@{
            if (busy || buffer.isEmpty()) return@submit
            val password = buffer.toCharArray()
            buffer.clear()
            length = 0
            onConfirm(password)
        },
    )
}
