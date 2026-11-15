package app.lumio

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
import app.lensly.core.btc.BtcNetwork
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcPortfolio
import app.lumio.btc.BtcSend
import app.lumio.btc.BtcSendInput
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.networkId
import app.lumio.data.provider.FeeEstimates
import app.lumio.data.provider.ProviderError
import app.lumio.data.sched.Priority
import app.lumio.evm.SignatureScan
import app.lumio.network.NetworkMode
import app.lumio.evm.SignatureStep
import app.lumio.ui.btc.BtcSendFormScreen
import app.lumio.ui.btc.BtcSendReviewScreen
import app.lumio.ui.btc.BtcSendSentScreen
import app.lumio.ui.btc.BtcSignRequestScreen
import app.lumio.ui.send.SignatureScanScreen
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens of a Bitcoin send, in the order the owner goes through them. */
private sealed interface BtcSendStep {
    data object Form : BtcSendStep

    class Review(val plan: BtcSendPlan) : BtcSendStep

    class Request(val plan: BtcSendPlan, val parts: List<String>) : BtcSendStep

    class Scanning(val plan: BtcSendPlan, val parts: List<String>) : BtcSendStep

    class Sent(val network: BtcNetwork, val txid: String) : BtcSendStep
}

private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NETWORK_FAILED = "The Bitcoin network could not be read. Try again."
private const val NO_BROWSER = "No browser can open the explorer."

/**
 * A Bitcoin send from one of the paired accounts: the form, the review, the request that Lensly signs, the scan of its signed answer, and the
 * transaction that is sent. Lumio holds no key. It builds the unsigned transaction from public reads, and it broadcasts only a signed answer
 * that carries the same transaction, with every input signed.
 */
@Composable
internal fun BtcSendRoute(navController: NavHostController, holder: VaultHolder, mode: NetworkMode) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    // Only the accounts of the side the owner chose. A mainnet send never offers a testnet account, and a testnet send never a mainnet one.
    val refs by produceState<List<BtcAccountRef>?>(initialValue = null, session, mode) {
        value = withContext(Dispatchers.IO) { bitcoinRefs(session).filter { mode.holds(it.account.network) } }
    }
    val accounts = refs ?: return

    var step by remember { mutableStateOf<BtcSendStep>(BtcSendStep.Form) }
    var accountKey by rememberSaveable { mutableStateOf<String?>(null) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var feeRate by rememberSaveable { mutableStateOf("") }
    var feeSuggestion by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentNotice by remember { mutableStateOf<String?>(null) }

    val selected = accounts.firstOrNull { it.key == accountKey } ?: accounts.firstOrNull()
    val network = selected?.account?.network

    // The fee rate starts at the server's estimate for the selected network. A rate the owner has typed is never replaced.
    LaunchedEffect(network) {
        val net = network ?: return@LaunchedEffect
        val estimates = runCatching {
            withContext(Dispatchers.IO) { app.scheduler.run(FeeEstimates(net.networkId()), Priority.FOREGROUND) }
        }.getOrNull() ?: return@LaunchedEffect
        val suggested = BtcSend.suggestedFeeRate(estimates).toLong().toString()
        feeSuggestion = "Starts at $suggested sat/vB, the server's estimate for about three blocks. Change it if you like."
        if (feeRate.isBlank()) feeRate = suggested
    }

    fun review() {
        val ref = selected ?: return
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val to = BtcSendInput.recipient(recipient, ref.account.network)
                    val sats = BtcSendInput.amountSats(amount)
                    val rate = BtcSendInput.feeRate(feeRate)
                    // Debug builds only: the pairing fixture's testnet4 coins, which no server holds. Release builds always read the network.
                    val spendable = DebugSpendFixture.spendable(ref.account) ?: BtcSend.spendable(ref.account, app.scheduler)
                    BtcSend.plan(ref.account, spendable, to, sats, rate)
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { plan -> step = BtcSendStep.Review(plan) },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
    }

    fun confirm(plan: BtcSendPlan) {
        runCatching { plan.parts() }
            .onSuccess { parts ->
                notice = null
                step = BtcSendStep.Request(plan, parts)
            }
            .onFailure { notice = REQUEST_FAILED }
    }

    fun startScan(current: BtcSendStep.Request) {
        scan = SignatureScan()
        scanProgress = null
        scanStatus = SCAN_HINT
        notice = null
        step = BtcSendStep.Scanning(current.plan, current.parts)
    }

    fun applySignature(current: BtcSendStep.Scanning, ur: UR) {
        scanBusy = true
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { BtcSend.signed(current.plan, ur) } }
            signed.fold(
                onFailure = { error ->
                    scanBusy = false
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { tx ->
                    val sent = withContext(Dispatchers.IO) {
                        runCatching { BtcSend.broadcast(app.scheduler, current.plan.network, tx) }
                    }
                    scanBusy = false
                    sent.fold(
                        onFailure = { error ->
                            notice = ownerWords(error, BROADCAST_FAILED)
                            step = BtcSendStep.Request(current.plan, current.parts)
                        },
                        onSuccess = { txid -> step = BtcSendStep.Sent(current.plan.network, txid) },
                    )
                },
            )
        }
    }

    fun onSignatureText(current: BtcSendStep.Scanning, text: String) {
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
            BtcSendStep.Form, is BtcSendStep.Sent -> navController.popBackStack()
            is BtcSendStep.Review -> {
                notice = null
                step = BtcSendStep.Form
            }
            is BtcSendStep.Request -> {
                notice = null
                step = BtcSendStep.Review(current.plan)
            }
            is BtcSendStep.Scanning -> step = BtcSendStep.Request(current.plan, current.parts)
        }
    }

    when (val current = step) {
        BtcSendStep.Form -> BtcSendFormScreen(
            accounts = accounts,
            selectedKey = selected?.key,
            onSelect = { accountKey = it },
            recipient = recipient,
            onRecipient = { recipient = it },
            amount = amount,
            onAmount = { amount = it },
            feeRate = feeRate,
            onFeeRate = { feeRate = it },
            feeSuggestion = feeSuggestion,
            busy = busy,
            notice = notice,
            onReview = ::review,
            onBack = { navController.popBackStack() },
        )
        is BtcSendStep.Review -> BtcSendReviewScreen(
            plan = current.plan,
            notice = notice,
            onConfirm = { confirm(current.plan) },
            onBack = {
                notice = null
                step = BtcSendStep.Form
            },
        )
        is BtcSendStep.Request -> BtcSignRequestScreen(
            network = current.plan.network,
            parts = current.parts,
            notice = notice,
            onScan = { startScan(current) },
            onBack = {
                notice = null
                step = BtcSendStep.Review(current.plan)
            },
        )
        is BtcSendStep.Scanning -> SignatureScanScreen(
            progress = scanProgress,
            status = scanStatus,
            onText = { text -> onSignatureText(current, text) },
            onCancel = { step = BtcSendStep.Request(current.plan, current.parts) },
        )
        is BtcSendStep.Sent -> BtcSendSentScreen(
            network = current.network,
            txid = current.txid,
            notice = sentNotice,
            onOpenExplorer = { openExplorer(BtcPortfolio.explorerTxUrl(current.network, current.txid)) },
            onDone = { navController.popBackStack() },
        )
    }
}

/** The words a failure shows: the owner's words for a refusal, a server's refusal, or a failed network read, else [fallback]. */
private fun ownerWords(error: Throwable, fallback: String): String = when (error) {
    is IllegalArgumentException -> error.message ?: fallback
    is ProviderError.Fatal -> error.message ?: fallback
    is ProviderError -> NETWORK_FAILED
    else -> fallback
}
