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
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.sign.BitcoinReviewScreen
import app.lensly.ui.sign.NoMatchingWalletScreen
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen

/** The view model key of a scan that is not for one wallet, so it never shares a flow with a wallet's scan. */
private const val ANY_WALLET_KEY = "sign-any-wallet"

/**
 * SIGN: scan the request, read the review, approve it with the App Password, then show the signature. Leaving the flow in any
 * way pops back to where it started.
 *
 * [walletId] is the wallet to sign with, as the wallet's own SIGN scan has it. Null is a scan of any wallet of the vault: the
 * wallet the request is for is found among them, and the owner is asked to scan again when none or several match.
 *
 * [frameSource] is passed to the scan step. Leave it null on a device: the camera feeds the scanner. The end-to-end test
 * passes its own source of generated frames, so the test drives this route instead of a copy of its screen mapping.
 */
@Composable
internal fun SignWalletRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String?,
    frameSource: FrameSource? = null,
) {
    val context = LocalContext.current.applicationContext
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(HiddenRoute.GATE)
    }

    val flow: SignFlowViewModel = viewModel(
        key = walletId ?: ANY_WALLET_KEY,
        factory = viewModelFactory {
            initializer { SignFlowViewModel(SignFlow(VaultSignPorts(context, holder), walletId)) }
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
            frameSource = frameSource,
        )
        is SignStep.Refused -> SignRefusedScreen(reason = shown.reason, call = shown.call, onDone = flow::leave)
        is SignStep.NoMatchingWallet -> NoMatchingWalletScreen(
            request = shown.request,
            several = shown.several,
            onScanAgain = flow::scanAgain,
            onCancel = flow::leave,
        )
        is SignStep.Review -> TxReviewScreen(
            review = shown.ready.review,
            onApprove = flow::approve,
            onReject = flow::leave,
        )
        is SignStep.Confirming -> ConfirmPasswordRoute(busy = shown.busy, onConfirm = flow::confirm)
        is SignStep.BitcoinReview -> BitcoinReviewScreen(
            review = shown.ready.reviewed.review,
            network = shown.ready.reviewed.account.network,
            scriptType = shown.ready.reviewed.account.scriptType,
            accountIndex = shown.ready.reviewed.account.index,
            confirmed = shown.confirmed,
            onConfirmedChange = flow::confirmUnverified,
            onApprove = flow::approve,
            onReject = flow::leave,
        )
        is SignStep.BitcoinConfirming -> ConfirmPasswordRoute(busy = shown.busy, onConfirm = flow::confirm)
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
