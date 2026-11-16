package app.lumio.send.algorand

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.qr.ScanScreen
import app.lumio.LumioApp
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.network.NetworkMode
import app.lumio.onVault
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens of an Algorand send, in the order the owner goes through them. */
private sealed interface AlgorandSendStep {
    data object Recipient : AlgorandSendStep

    /** The camera reads a recipient address. */
    data object ScanRecipient : AlgorandSendStep

    data object Amount : AlgorandSendStep

    class Review(val plan: AlgorandSendPlan) : AlgorandSendStep

    class Request(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>) : AlgorandSendStep

    class Scanning(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>) : AlgorandSendStep

    class Sent(val plan: AlgorandSendPlan, val txid: String) : AlgorandSendStep
}

private const val RECIPIENT_HINT = "Point the camera at an Algorand address."
private const val RECIPIENT_REFUSED = "That code is not an Algorand address. Point the camera at an address."
private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_SENDING = "Signature read. Sending the transaction to the network."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The network did not accept the transaction. Check the balance, then try again."
private const val READ_FAILED = "The Algorand network could not be read. Try again."
private const val PENDING_TEXT = "Waiting for the network to confirm the transaction."
private const val NOT_IN_POOL_TEXT = "The network no longer holds the transaction. Check the explorer for its status."
private const val POOL_READ_FAILED_TEXT = "The network could not be read. Check the explorer for the transaction."
private const val NO_BROWSER = "No browser can open the explorer."
private const val REQUEST_ID_BYTES = 16
private const val POLL_MS = 3_000L
private const val POLL_LIMIT = 20

/**
 * An Algorand send from one account of a paired wallet: the recipient, the amount, the review, the request that Lensly signs, the scan of its
 * signature, and the transaction that is sent. The account is fixed by the caller, and the network is the side the owner chose. Lumio holds no key.
 */
@Composable
internal fun AlgorandSendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    mode: NetworkMode,
    walletId: String,
    accountIndex: Int,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    val paired by produceState<PairedWallet?>(initialValue = null, session, walletId) {
        value = onVault(null) { PairedWallets.list(session).firstOrNull { it.id == walletId } }
    }
    val wallet = paired ?: return
    val signer = wallet.algorand.getOrNull(accountIndex) ?: return
    val address = AlgorandAccount.addressOf(signer)
    val network = if (mode.holds(ALGORAND_TESTNET.testnet)) ALGORAND_TESTNET else ALGORAND_MAINNET
    val scheduler = app.nativeSchedulerFor(session)

    var step by remember { mutableStateOf<AlgorandSendStep>(AlgorandSendStep.Recipient) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var funds by remember { mutableStateOf<AlgorandFunds?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientStatus by remember { mutableStateOf(RECIPIENT_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentStatus by remember { mutableStateOf(PENDING_TEXT) }
    var sentNotice by remember { mutableStateOf<String?>(null) }

    val to: ByteArray? = runCatching { AlgorandSend.recipientKey(recipient) }.getOrNull()
    val loaded = funds
    // The fee the send would pay, and the most it can send, both worked out from the loaded parameters. A fee the parameters cannot give is unknown.
    val feeEstimate: BigInteger? = if (loaded != null && to != null) {
        runCatching { AlgorandSend.estimateFee(loaded, signer, to, BigInteger.ONE) }.getOrNull()
    } else {
        null
    }
    val maxMicro: BigInteger? = if (loaded != null && feeEstimate != null) AlgorandSend.maxMicro(loaded.balance, feeEstimate) else null
    val amountError = amountError(amount, maxMicro)
    val canReview = loaded != null && to != null && amount.isNotBlank() && amountError == null

    // The balance and the parameters are read when the amount step opens. A failed read keeps the step, with the words why.
    LaunchedEffect(step) {
        if (step !is AlgorandSendStep.Amount) return@LaunchedEffect
        funds = null
        runCatching { withContext(Dispatchers.IO) { AlgorandSend.funds(scheduler, network, address) } }
            .onSuccess { funds = it }
            .onFailure { notice = AlgorandSend.ownerWords(it, READ_FAILED) }
    }

    // The status of the sent transaction, read from the pool until the network confirms it or refuses it, or the pool no longer holds it.
    val sent = step as? AlgorandSendStep.Sent
    LaunchedEffect(sent?.txid) {
        val txid = sent?.txid ?: return@LaunchedEffect
        for (attempt in 0 until POLL_LIMIT) {
            val read = withContext(Dispatchers.IO) { runCatching { AlgorandSend.pendingState(scheduler, network, txid) } }
            if (read.isFailure) {
                sentStatus = POOL_READ_FAILED_TEXT
            } else {
                val info = read.getOrNull()
                when {
                    info == null -> {
                        sentStatus = NOT_IN_POOL_TEXT
                        return@LaunchedEffect
                    }
                    info.poolError.isNotEmpty() -> {
                        sentStatus = "The network refused the transaction: ${info.poolError}"
                        return@LaunchedEffect
                    }
                    info.confirmedRound > 0 -> {
                        sentStatus = "Confirmed in round ${info.confirmedRound}."
                        return@LaunchedEffect
                    }
                    else -> sentStatus = PENDING_TEXT
                }
            }
            if (attempt < POLL_LIMIT - 1) delay(POLL_MS)
        }
    }

    fun review() {
        val read = funds ?: return
        val key = to ?: return
        runCatching { AlgorandSend.plan(network, read, wallet.fingerprint, accountIndex, signer, key, AlgorandSend.microAlgos(amount)) }
            .onSuccess { plan ->
                notice = null
                step = AlgorandSendStep.Review(plan)
            }
            .onFailure { error -> notice = AlgorandSend.ownerWords(error, PREPARE_FAILED) }
    }

    fun confirm(plan: AlgorandSendPlan) {
        val requestId = ByteArray(REQUEST_ID_BYTES).also { SecureRandom().nextBytes(it) }
        runCatching { AlgorandSend.requestParts(plan, requestId) }
            .onSuccess { parts ->
                notice = null
                step = AlgorandSendStep.Request(plan, requestId, parts)
            }
            .onFailure { notice = REQUEST_FAILED }
    }

    /** A scanned recipient fills To. A code that is not an Algorand address keeps the camera on, with the words why. */
    fun onRecipientText(text: String) {
        val trimmed = text.trim()
        if (AlgorandSend.recipientError(trimmed) == null) {
            recipient = trimmed
            recipientStatus = RECIPIENT_HINT
            step = AlgorandSendStep.Recipient
        } else {
            recipientStatus = RECIPIENT_REFUSED
        }
    }

    fun startScan(current: AlgorandSendStep.Request) {
        scan = SignatureScan()
        scanProgress = null
        scanStatus = SCAN_HINT
        notice = null
        step = AlgorandSendStep.Scanning(current.plan, current.requestId, current.parts)
    }

    /** The signature is checked before anything is sent. A refused signature keeps the scan open, with the words why. */
    fun applySignature(current: AlgorandSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_SENDING
        scope.launch {
            val signed = withContext(Dispatchers.Default) {
                runCatching { AlgorandSend.signed(current.plan, current.requestId, ur) }
            }
            signed.fold(
                onFailure = { error ->
                    scanBusy = false
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = AlgorandSend.ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { bytes ->
                    val broadcast = withContext(Dispatchers.IO) {
                        runCatching { AlgorandSend.broadcast(scheduler, current.plan, bytes) }
                    }
                    scanBusy = false
                    broadcast.fold(
                        onFailure = { error ->
                            notice = if (error is ProviderError.Fatal) BROADCAST_FAILED else AlgorandSend.ownerWords(error, BROADCAST_FAILED)
                            step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts)
                        },
                        onSuccess = { txid ->
                            sentStatus = PENDING_TEXT
                            sentNotice = null
                            step = AlgorandSendStep.Sent(current.plan, txid)
                        },
                    )
                },
            )
        }
    }

    fun onSignatureText(current: AlgorandSendStep.Scanning, text: String) {
        if (scanBusy) return
        when (val result = scan.onText(text)) {
            is SignatureStep.Reading -> {
                scanProgress = result.fraction
                scanStatus = SCAN_READING
            }
            is SignatureStep.Complete -> applySignature(current, result.ur)
            SignatureStep.Ignored -> Unit
        }
    }

    fun openExplorer(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            sentNotice = NO_BROWSER
        }
    }

    BackHandler {
        when (val current = step) {
            AlgorandSendStep.Recipient, is AlgorandSendStep.Sent -> navController.popBackStack()
            AlgorandSendStep.ScanRecipient, AlgorandSendStep.Amount -> step = AlgorandSendStep.Recipient
            is AlgorandSendStep.Review -> {
                notice = null
                step = AlgorandSendStep.Amount
            }
            is AlgorandSendStep.Request -> {
                notice = null
                step = AlgorandSendStep.Review(current.plan)
            }
            is AlgorandSendStep.Scanning -> step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts)
        }
    }

    when (val current = step) {
        AlgorandSendStep.Recipient -> AlgorandRecipientScreen(
            network = network,
            recipient = recipient,
            onRecipient = { recipient = it },
            onScan = {
                recipientStatus = RECIPIENT_HINT
                step = AlgorandSendStep.ScanRecipient
            },
            onContinue = {
                notice = null
                step = AlgorandSendStep.Amount
            },
            onBack = { navController.popBackStack() },
        )
        AlgorandSendStep.ScanRecipient -> ScanScreen(
            title = "Scan address",
            progress = null,
            statusText = recipientStatus,
            onText = ::onRecipientText,
            onCancel = { step = AlgorandSendStep.Recipient },
        )
        AlgorandSendStep.Amount -> AlgorandAmountScreen(
            network = network,
            toAddress = recipient.trim(),
            balance = loaded?.balance,
            feeEstimate = feeEstimate,
            maxEnabled = maxMicro != null && maxMicro.signum() > 0,
            amount = amount,
            onAmount = { amount = it },
            onMax = { maxMicro?.let { amount = AlgorandSend.algoText(it) } },
            error = amountError,
            notice = notice,
            canReview = canReview,
            onReview = ::review,
            onBack = {
                notice = null
                step = AlgorandSendStep.Recipient
            },
        )
        is AlgorandSendStep.Review -> AlgorandReviewScreen(
            plan = current.plan,
            notice = notice,
            onConfirm = { confirm(current.plan) },
            onBack = {
                notice = null
                step = AlgorandSendStep.Amount
            },
        )
        is AlgorandSendStep.Request -> AlgorandSignRequestScreen(
            network = current.plan.network,
            parts = current.parts,
            notice = notice,
            onScan = { startScan(current) },
            onBack = {
                notice = null
                step = AlgorandSendStep.Review(current.plan)
            },
        )
        is AlgorandSendStep.Scanning -> ScanScreen(
            title = "Scan signature",
            progress = scanProgress,
            statusText = scanStatus,
            onText = { text -> onSignatureText(current, text) },
            onCancel = { step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts) },
        )
        is AlgorandSendStep.Sent -> AlgorandSentScreen(
            network = current.plan.network,
            txid = current.txid,
            status = sentStatus,
            notice = sentNotice,
            onOpenExplorer = { openExplorer(AlgorandSend.explorerUrl(current.plan.network, current.txid)) },
            onDone = { navController.popBackStack() },
        )
    }
}

/** The reason [text] cannot be sent, given the most that can be: null when it can, or when nothing is typed yet. */
private fun amountError(text: String, max: BigInteger?): String? {
    if (text.isBlank()) return null
    val micro = runCatching { AlgorandSend.microAlgos(text) }.getOrElse { return it.message ?: "Enter an amount in ALGO." }
    if (max != null && micro > max) return "More than you can send. The most is ${AlgorandSend.algoText(max)} ALGO."
    return null
}
