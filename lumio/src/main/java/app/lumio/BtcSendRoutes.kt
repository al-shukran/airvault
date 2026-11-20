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
import app.lensly.DebugBtcFixture
import app.airvault.core.model.AccountId
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcPortfolio
import app.lumio.btc.BtcReadException
import app.lumio.btc.BtcSend
import app.lumio.btc.BtcSendInput
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.BtcSpendable
import app.lumio.btc.FALLBACK_FEE_RATE_SAT_PER_VB
import app.lumio.btc.indexerOf
import app.lumio.btc.networkId
import app.lumio.data.provider.FeeEstimates
import app.lumio.data.provider.ProviderError
import app.lumio.data.sched.Priority
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.send.BtcPaymentCode
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.ui.btc.BtcSendFormScreen
import app.lumio.ui.btc.BtcSendReviewScreen
import app.lumio.ui.btc.BtcSignRequestScreen
import app.lumio.ui.send.RECIPIENT_SCAN_HINT
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SignatureScanScreen
import app.lumio.ui.send.SubmitScreen
import app.lumio.ui.send.btcSummary
import app.lumio.wallets.WalletAccounts
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** The screens of a Bitcoin send, in the order the owner goes through them. */
private sealed interface BtcSendStep {
    data object Form : BtcSendStep

    /** The camera reads a recipient: an address, or a payment code that fills the form. */
    data object ScanRecipient : BtcSendStep

    class Review(val plan: BtcSendPlan) : BtcSendStep

    class Request(val plan: BtcSendPlan, val parts: List<String>) : BtcSendStep

    class Scanning(val plan: BtcSendPlan, val parts: List<String>) : BtcSendStep

    /** The signed transaction, held in memory until the owner submits it. Leaving the screen discards it. */
    class Submit(val plan: BtcSendPlan, val parts: List<String>, val machine: SubmitMachine<*, String>) : BtcSendStep
}

private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_READ = "Signature read. Check the transaction, then submit it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NETWORK_FAILED = "The Bitcoin network could not be read. Try again."
private const val NO_BROWSER = "No browser can open the explorer."

/** The account a Bitcoin send is from: [Missing] when the vault has no such account on the side the owner chose. */
private sealed interface BtcSendLoad {
    data object Missing : BtcSendLoad

    class Ready(val account: BtcAccountRef) : BtcSendLoad
}

/**
 * A Bitcoin send from the paired [account] of the wallet [walletId], which is fixed: the form, the review, the request that Lensly
 * signs, the scan of its signed answer, and the transaction that is sent. Lumio holds no key. It builds the unsigned transaction from public reads,
 * and it broadcasts only a signed answer that carries the same transaction, with every input signed.
 */
@Composable
internal fun BtcSendRoute(navController: NavHostController, holder: VaultHolder, walletId: String, account: AccountId, mode: NetworkMode) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    // The account [account] of the wallet, on the side the owner chose: a mainnet send never opens a testnet account, and a testnet send never a mainnet one.
    val load by produceState<BtcSendLoad?>(initialValue = null, session, walletId, account, mode) {
        value = withContext(Dispatchers.IO) {
            val index = PairedWallets.get(session, walletId)?.let { WalletAccounts.indexOf(it, account) }
            index?.let { bitcoinAccountRef(session, walletId, it, mode) }?.let { BtcSendLoad.Ready(it) } ?: BtcSendLoad.Missing
        }
    }
    // The paired wallets, for the names of the review: the Address Book of the wallet that holds the plan's account.
    val wallets by produceState<List<PairedWallet>>(initialValue = emptyList(), session) {
        value = onVault(emptyList()) { PairedWallets.list(session) }
    }
    if (load == BtcSendLoad.Missing) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val selected = (load as? BtcSendLoad.Ready)?.account ?: return

    var step by remember { mutableStateOf<BtcSendStep>(BtcSendStep.Form) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var feeRate by rememberSaveable { mutableStateOf("") }
    var feeSuggestion by remember { mutableStateOf<String?>(null) }
    // Whether the owner has changed the fee field. Once they have, no suggestion replaces what they typed.
    var feeEdited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentNotice by remember { mutableStateOf<String?>(null) }
    // The server's fee estimate for the network, and the coins the send may spend. The review's Advanced sheet re-plans on these.
    var suggestedRate by remember { mutableStateOf<Double?>(null) }
    var spendable by remember { mutableStateOf<BtcSpendable?>(null) }

    val network = selected.account.network

    // The fee rate is optional: the form starts at the server's estimate for about 30 minutes, or at the fallback rate while that is read or when
    // no estimate comes. A rate the owner has typed is never replaced, and a blank field means the suggestion.
    LaunchedEffect(network) {
        if (feeRate.isBlank() && !feeEdited) feeRate = BtcSend.formatFeeRate(FALLBACK_FEE_RATE_SAT_PER_VB)
        val estimates = runCatching {
            withContext(Dispatchers.IO) { app.scheduler.run(FeeEstimates(network.networkId()), Priority.FOREGROUND) }
        }.getOrNull()
        val suggestion = BtcSend.feeSuggestion(estimates)
        suggestedRate = suggestion.satPerVb
        feeSuggestion = if (suggestion.fromEstimate) {
            "Starts at ${BtcSend.formatFeeRate(suggestion.satPerVb)} sat/vB, the server's estimate for about 30 minutes. Change it, or leave it to use this rate."
        } else {
            "The server gave no estimate. Starts at ${BtcSend.formatFeeRate(suggestion.satPerVb)} sat/vB, the fallback rate. Change it, or leave it to use this rate."
        }
        feeRate = BtcSend.fieldAfterSuggestion(feeRate, feeEdited, suggestion.satPerVb)
    }

    fun review() {
        val ref = selected
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val to = BtcSendInput.recipient(recipient, ref.account.network)
                    val sats = BtcSendInput.amountSats(amount)
                    val rate = BtcSendInput.feeRate(feeRate.ifBlank { BtcSend.formatFeeRate(suggestedRate ?: FALLBACK_FEE_RATE_SAT_PER_VB) })
                    // The owner's Ordinals indexer, when one is set, marks each output it finds an inscription or a rune on (spec 5.5).
                    val indexer = app.lumioVault()?.let { indexerOf(it.configs(), app.http) }
                    // Debug builds only: the pairing fixture's testnet4 coins, which no server holds. Release builds always read the network.
                    val pool = DebugSpendFixture.spendable(ref.account)
                        ?: BtcSend.spendable(ref.account, app.scheduler, app.addressStats.forNetwork(network.networkId()), indexer = indexer)
                    pool to BtcSend.plan(ref.account, pool, to, sats, rate)
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { (pool, plan) ->
                    spendable = pool
                    step = BtcSendStep.Review(plan)
                },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
    }

    /** The send rebuilt at [rate] from the same coins, as the review's Advanced sheet saves it. A refused rate keeps the review and says why. */
    fun saveFeeRate(current: BtcSendPlan, rate: Double) {
        val pool = spendable ?: return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { BtcSend.plan(current.account, pool, current.recipient, current.amountSats, rate) }
            }
            outcome.fold(
                onSuccess = { plan ->
                    notice = null
                    step = BtcSendStep.Review(plan)
                },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
    }

    /** A scanned recipient fills To, and the amount when the form has none. A code that is refused keeps the camera on, with the words why. */
    fun onRecipientText(text: String) {
        runCatching { BtcPaymentCode.read(text, network) }
            .onSuccess { payment ->
                recipient = payment.address
                if (payment.amount != null && amount.isBlank()) amount = payment.amount
                notice = null
                step = BtcSendStep.Form
            }
            .onFailure { error -> scanStatus = ownerWords(error, RECIPIENT_SCAN_HINT) }
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

    /** Checks Lensly's signature and holds the signed transaction. Nothing is sent until the owner submits it on the next screen. */
    fun applySignature(current: BtcSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { BtcSend.signed(current.plan, ur) } }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { tx ->
                    val network = current.plan.network
                    val machine = SubmitMachine(
                        signed = tx,
                        broadcast = { signedTx -> withContext(Dispatchers.IO) { BtcSend.broadcast(app.scheduler, network, signedTx) } },
                        describe = { error -> SubmitFailure(ownerWords(error, BROADCAST_FAILED)) },
                    )
                    notice = null
                    step = BtcSendStep.Submit(current.plan, current.parts, machine)
                },
            )
        }
    }

    /** Sends the signed transaction. A retry sends the same signed bytes again. */
    fun submit(current: BtcSendStep.Submit) {
        scope.launch { current.machine.submit() }
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

    // A debug build can read a fixture in place of the camera, so the signature can be shown without one. Release builds have no fixture.
    LaunchedEffect(step) {
        val current = step
        if (current is BtcSendStep.Scanning) DebugBtcFixture.take()?.split(' ')?.forEach { onSignatureText(current, it) }
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
            BtcSendStep.Form -> navController.popBackStack()
            BtcSendStep.ScanRecipient -> step = BtcSendStep.Form
            is BtcSendStep.Review -> {
                notice = null
                step = BtcSendStep.Form
            }
            is BtcSendStep.Request -> {
                notice = null
                step = BtcSendStep.Review(current.plan)
            }
            is BtcSendStep.Scanning -> step = BtcSendStep.Request(current.plan, current.parts)
            is BtcSendStep.Submit -> when (current.machine.state.value) {
                SubmitState.Submitting -> Unit
                is SubmitState.Sent -> navController.popBackStack()
                else -> {
                    notice = null
                    step = BtcSendStep.Request(current.plan, current.parts)
                }
            }
        }
    }

    when (val current = step) {
        BtcSendStep.Form -> BtcSendFormScreen(
            account = selected,
            recipient = recipient,
            onRecipient = { recipient = it },
            onScan = {
                scanStatus = RECIPIENT_SCAN_HINT
                step = BtcSendStep.ScanRecipient
            },
            amount = amount,
            onAmount = { amount = it },
            feeRate = feeRate,
            onFeeRate = {
                feeRate = it
                feeEdited = true
            },
            feeSuggestion = feeSuggestion,
            busy = busy,
            notice = notice,
            onReview = ::review,
            onBack = { navController.popBackStack() },
        )
        BtcSendStep.ScanRecipient -> RecipientScanScreen(
            status = scanStatus,
            onText = ::onRecipientText,
            onCancel = { step = BtcSendStep.Form },
        )
        is BtcSendStep.Review -> BtcSendReviewScreen(
            plan = current.plan,
            suggestedRate = suggestedRate ?: current.plan.feeRateSatPerVb,
            addressBook = wallets.firstOrNull { it.fingerprint == current.plan.account.masterFingerprint }?.addressBook.orEmpty(),
            notice = notice,
            onSave = { rate -> saveFeeRate(current.plan, rate) },
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
        is BtcSendStep.Submit -> SubmitScreen(
            machine = current.machine,
            rows = btcSummary(current.plan),
            explorerUrl = { txid -> BtcPortfolio.explorerTxUrl(current.plan.network, txid) },
            status = null,
            explorerNotice = sentNotice,
            onSubmit = { submit(current) },
            onOpenExplorer = { txid -> openExplorer(BtcPortfolio.explorerTxUrl(current.plan.network, txid)) },
            onStartOver = {
                notice = null
                step = BtcSendStep.Form
            },
            onBack = {
                notice = null
                step = BtcSendStep.Request(current.plan, current.parts)
            },
            onDone = { navController.popBackStack() },
        )
    }
}

/** The words a failure shows: the owner's words for a refusal, a server's refusal, or a failed network read, else [fallback]. */
private fun ownerWords(error: Throwable, fallback: String): String = when (error) {
    is IllegalArgumentException -> error.message ?: fallback
    // A read names the step that failed, so the owner sees what Lumio was reading and why.
    is BtcReadException -> error.message ?: fallback
    is ProviderError.Fatal -> error.message ?: fallback
    is ProviderError -> NETWORK_FAILED
    else -> fallback
}
