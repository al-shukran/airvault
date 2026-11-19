package app.lensly.ui.flow

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import app.airvault.core.model.ChainId
import app.lensly.DebugBtcFixture
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.VaultHolder
import app.lensly.app.addressNotesOf
import app.lensly.app.notesOf
import app.lensly.app.sign.algorandCounterparties
import app.airvault.chains.algorand.signer.toReview
import app.lensly.app.sign.solanaCounterparties
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.gate.eraseWarning
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.navigation.openAddressBookFor
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.navigation.rememberAddressBook
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.sign.ReviewScreen
import app.lensly.ui.sign.SolanaReviewScreen
import app.lensly.ui.sign.SignRefusedScreen
import app.lensly.ui.sign.TxReviewScreen
import app.lensly.ui.sign.bitcoinAcknowledgement
import app.lensly.ui.sign.bitcoinReviewOf
import app.lensly.ui.sign.shownName


/** The view model key of a scan that is not for one wallet, so it never shares a flow with a wallet's scan. */
private const val ANY_WALLET_KEY = "sign-any-wallet"

/**
 * SIGN: scan the request, read the review, approve it with the App Password, then show the signature. Leaving the flow in any
 * way pops back to where it started.
 *
 * [walletId] is the wallet to sign with, as the wallet's own SIGN scan has it. Null is a scan of any wallet of the vault: the
 * wallet the request is for is found among them, and the request is refused in plain words when none or several match.
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
    // The Address Book is read again each time this screen is shown, so an entry saved on the form shows on the review it came from.
    val book = session?.let { rememberAddressBook(navController, it) }
    val openAddress: (String, ChainId) -> Unit = { address, chain -> navController.openAddressBookFor(book.orEmpty(), address, chain) }

    val flow: SignFlowViewModel = viewModel(
        key = walletId ?: ANY_WALLET_KEY,
        factory = viewModelFactory {
            initializer { SignFlowViewModel(SignFlow(VaultSignPorts(context, holder), walletId)) }
        },
    )
    val step by flow.state.collectAsState()
    // A debug build may offer a fixture request to a wallet's sign screen, which reads it as the first scan. Release offers none.
    LaunchedEffect(flow) {
        if (walletId != null) DebugBtcFixture.take()?.split(' ')?.forEach { flow.onScanned(it) }
    }
    BackHandler { flow.back() }
    LaunchedEffect(step) {
        if (step == SignStep.Closed) navController.popBackStack()
    }

    when (val shown = step) {
        is SignStep.Scanning -> ScanScreen(
            title = "Sign Request",
            progress = shown.progress,
            statusText = shown.status,
            onText = flow::onScanned,
            onCancel = flow::leave,
            frameSource = frameSource,
        )
        is SignStep.Refused -> SignRefusedScreen(reason = shown.reason, call = shown.call, onDone = flow::leave)
        is SignStep.Review -> TxReviewScreen(
            review = shown.ready.review.copy(
                addressNotes = notesNow(shown.ready.review.addressNotes, shown.ready.review.counterparties, EVM_FAMILY, book),
            ),
            onApprove = flow::approve,
            onReject = flow::leave,
            onOpenAddress = openAddress,
        )
        is SignStep.Confirming -> ConfirmPasswordRoute(busy = shown.busy, holder = holder, onConfirm = flow::confirm)
        is SignStep.BitcoinReview -> {
            val reviewed = shown.ready.reviewed
            val notes = notesNow(
                reviewed.addressNotes,
                reviewed.review.outputs.mapNotNull { it.address },
                BITCOIN_FAMILY,
                book,
            )
            ReviewScreen(
                review = remember(reviewed.review, reviewed.account.network, reviewed.account.scriptType, notes) {
                    bitcoinReviewOf(reviewed.review, reviewed.account.network, reviewed.account.scriptType) { address ->
                        notes.notesOf(address)?.shownName()
                    }
                },
                signer = reviewed.signer,
                addressNotes = notes,
                addressChain = BITCOIN_FAMILY,
                acknowledge = bitcoinAcknowledgement(reviewed.review, shown.confirmed, flow::confirmUnverified),
                onApprove = flow::approve,
                onReject = flow::leave,
                onOpenAddress = openAddress,
            )
        }
        is SignStep.BitcoinConfirming -> ConfirmPasswordRoute(busy = shown.busy, holder = holder, onConfirm = flow::confirm)
        is SignStep.SolanaReview -> SolanaReviewScreen(
            review = shown.ready.review,
            signer = shown.ready.signer,
            addressNotes = notesNow(shown.ready.addressNotes, solanaCounterparties(shown.ready.review), SOLANA_FAMILY, book),
            onApprove = flow::approve,
            onReject = flow::leave,
            onOpenAddress = openAddress,
        )
        is SignStep.SolanaConfirming -> ConfirmPasswordRoute(busy = shown.busy, holder = holder, onConfirm = flow::confirm)
        is SignStep.AlgorandReview -> ReviewScreen(
            review = remember(shown.ready.review) { shown.ready.review.toReview() },
            signer = shown.ready.signer,
            addressNotes = notesNow(shown.ready.addressNotes, algorandCounterparties(shown.ready.review), ALGORAND_FAMILY, book),
            addressChain = ALGORAND_FAMILY,
            onApprove = flow::approve,
            onReject = flow::leave,
            onOpenAddress = openAddress,
        )
        is SignStep.AlgorandConfirming -> ConfirmPasswordRoute(busy = shown.busy, holder = holder, onConfirm = flow::confirm)
        is SignStep.Signature -> ShowQrScreen(
            title = "Signature",
            subtitle = "Scan this with your online wallet",
            parts = shown.parts,
            onDone = flow::leave,
        )
        SignStep.Closed -> Unit
    }
}

/**
 * The notes of [addresses] on [chain] as the Address Book holds them now. The device's accounts keep the notes [read] took when the review
 * was made, since they do not change while a request is open. Until the Address Book is read, [read] is shown as it is.
 */
private fun notesNow(
    read: Map<String, AddressNotes>,
    addresses: List<String>,
    chain: ChainId,
    book: List<AddressBookEntry>?,
): Map<String, AddressNotes> {
    if (book == null) return read
    val internal = read.mapNotNull { (key, notes) -> notes.internal?.let { key to it } }.toMap()
    return addressNotesOf(addresses, chain, internal, book)
}

/**
 * The App Password check before a signature. Like the unlock screen, it shows no error: a wrong password clears the field.
 * The typed characters are copied out, and the buffer is cleared, on every submit.
 */
@Composable
private fun ConfirmPasswordRoute(busy: Boolean, holder: VaultHolder, onConfirm: (CharArray) -> Unit) {
    val buffer = remember { PasswordBuffer() }
    var length by remember { mutableIntStateOf(0) }
    var triesLeft by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        onDispose { buffer.clear() }
    }
    // The tries left are read each time the App Password is asked, so the warning counts the wrong tries just made.
    LaunchedEffect(busy) { if (!busy) triesLeft = holder.vault.triesBeforeErase() }

    ConfirmPasswordScreen(
        length = length,
        busy = busy,
        warning = triesLeft?.let(::eraseWarning),
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
