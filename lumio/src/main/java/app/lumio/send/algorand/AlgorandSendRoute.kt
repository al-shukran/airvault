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
import app.lumio.chains.ChainHoldings
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaHoldings
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.network.NetworkMode
import app.lumio.onVault
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.ui.send.SubmitScreen
import app.lumio.ui.send.algorandSummary
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens of an Algorand send, in the order the owner goes through them. The recipient and the amount are on the Details step. */
private sealed interface AlgorandSendStep {
    data object Form : AlgorandSendStep

    /** The camera reads a recipient address. */
    data object ScanRecipient : AlgorandSendStep

    class Review(val plan: AlgorandSendPlan) : AlgorandSendStep

    class Request(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>) : AlgorandSendStep

    class Scanning(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>) : AlgorandSendStep

    /** The signed transaction, held in memory until the owner submits it. Leaving the screen discards it. */
    class Submit(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>, val machine: SubmitMachine<*, String>) : AlgorandSendStep
}

private const val RECIPIENT_HINT = "Point the camera at an Algorand address."
private const val RECIPIENT_REFUSED = "That code is not an Algorand address. Point the camera at an address."
private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_READ = "Signature read. Check the transaction, then submit it."
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
 * An Algorand send from one account of a paired wallet: the details (the coin, the recipient and the amount), the review, the request that Lensly signs,
 * the scan of its signature, and the transaction that is sent. The account is fixed by the caller, and the network is the side the owner chose. The coins
 * are ALGO, the assets the account holds, and USDC to opt in to. Lumio holds no key.
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

    var step by remember { mutableStateOf<AlgorandSendStep>(AlgorandSendStep.Form) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    // The coin the send moves, by its key: ALGO, or the ID of an asset. It is saved, so that a rotation keeps the owner's choice.
    var selectedKey by rememberSaveable { mutableStateOf(AlgorandAssets.ALGO_KEY) }
    var assets by remember { mutableStateOf(listOf(AlgorandAssets.native)) }
    var funds by remember { mutableStateOf<AlgorandFunds?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientStatus by remember { mutableStateOf(RECIPIENT_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentStatus by remember { mutableStateOf(PENDING_TEXT) }
    var sentNotice by remember { mutableStateOf<String?>(null) }
    var sentTxid by remember { mutableStateOf<String?>(null) }

    val selected = assets.firstOrNull { it.key == selectedKey } ?: AlgorandAssets.native
    val optIn = AlgorandAssets.needsOptIn(selected)
    val loaded = funds
    val to: ByteArray? = runCatching { AlgorandSend.recipientKey(recipient) }.getOrNull()
    val isAlgo = selected.assetId == null
    // The fee of an ALGO send and the most that can be sent, both worked out from the loaded parameters. An asset's most is what the account holds.
    val feeEstimate: BigInteger? = if (loaded != null && to != null && isAlgo) {
        runCatching { AlgorandSend.estimateFee(loaded, signer, to, BigInteger.ONE) }.getOrNull()
    } else {
        null
    }
    val maxUnits: BigInteger? = when {
        loaded == null -> null
        isAlgo -> feeEstimate?.let { AlgorandSend.maxMicro(loaded.balance, it) }
        else -> selected.held
    }
    val amountError = amountError(amount, selected, maxUnits)
    val canReview = loaded != null && !busy && (optIn || (to != null && amount.isNotBlank() && amountError == null))

    // The balance, the parameters and the coins are read when the details open. A failed read keeps the form, with the words why.
    LaunchedEffect(step) {
        if (step !is AlgorandSendStep.Form) return@LaunchedEffect
        funds = null
        runCatching {
            withContext(Dispatchers.IO) {
                val known = AlgorandAssets.knownAssets(network)
                val holdings = optedInAssets(scheduler, network, address)
                val infos = AlgorandAssets.infoIds(holdings, known).associateWith { ChainHoldings.info(scheduler, network, it) }
                AlgorandSend.funds(scheduler, network, address) to AlgorandAssets.choices(holdings, known, infos)
            }
        }
            .onSuccess { (read, choices) ->
                funds = read
                assets = choices
            }
            .onFailure { notice = AlgorandSend.ownerWords(it, READ_FAILED) }
    }

    // The status of the sent transaction, read from the pool until the network confirms it or refuses it, or the pool no longer holds it.
    LaunchedEffect(sentTxid) {
        val txid = sentTxid ?: return@LaunchedEffect
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

    /**
     * The plan of the send, routed by the coin: an opt-in for a coin the account has not opted in to, else a payment of ALGO or a transfer of the asset. A
     * transfer first reads whether the recipient has opted in to the asset, since the network refuses a transfer to one who has not.
     */
    fun review() {
        val read = loaded ?: return
        val asset = selected
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (AlgorandAssets.needsOptIn(asset)) {
                        AlgorandAssets.plan(asset, network, read, wallet.fingerprint, accountIndex, signer, signer, BigInteger.ZERO, recipientOptedIn = false)
                    } else {
                        val key = AlgorandSend.recipientKey(recipient)
                        val units = AlgorandAssets.units(amount, asset.decimals, asset.symbol)
                        val recipientOptedIn = asset.assetId?.let { id ->
                            AlgorandAssets.optedIn(id, optedInAssets(scheduler, network, AlgorandAccount.addressOf(key)))
                        } ?: true
                        AlgorandAssets.plan(asset, network, read, wallet.fingerprint, accountIndex, signer, key, units, recipientOptedIn)
                    }
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { plan ->
                    notice = null
                    step = AlgorandSendStep.Review(plan)
                },
                onFailure = { error -> notice = AlgorandSend.ownerWords(error, PREPARE_FAILED) },
            )
        }
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
            step = AlgorandSendStep.Form
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

    /** The signature is checked before anything is held. A refused signature keeps the scan open, with the words why. */
    fun applySignature(current: AlgorandSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.Default) {
                runCatching { AlgorandSend.signed(current.plan, current.requestId, ur) }
            }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = AlgorandSend.ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { bytes ->
                    val machine = SubmitMachine(
                        signed = bytes,
                        broadcast = { signedBytes -> withContext(Dispatchers.IO) { AlgorandSend.broadcast(scheduler, current.plan, signedBytes) } },
                        describe = { error ->
                            SubmitFailure(if (error is ProviderError.Fatal) BROADCAST_FAILED else AlgorandSend.ownerWords(error, BROADCAST_FAILED))
                        },
                    )
                    notice = null
                    step = AlgorandSendStep.Submit(current.plan, current.requestId, current.parts, machine)
                },
            )
        }
    }

    /** Sends the signed transaction, then the pool is read for its status. A retry sends the same signed bytes again. */
    fun submit(current: AlgorandSendStep.Submit) {
        scope.launch {
            current.machine.submit()
            val state = current.machine.state.value
            if (state is SubmitState.Sent) {
                sentStatus = PENDING_TEXT
                sentNotice = null
                sentTxid = state.id
            }
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
            AlgorandSendStep.Form -> navController.popBackStack()
            AlgorandSendStep.ScanRecipient -> step = AlgorandSendStep.Form
            is AlgorandSendStep.Review -> {
                notice = null
                step = AlgorandSendStep.Form
            }
            is AlgorandSendStep.Request -> {
                notice = null
                step = AlgorandSendStep.Review(current.plan)
            }
            is AlgorandSendStep.Scanning -> step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts)
            is AlgorandSendStep.Submit -> when (current.machine.state.value) {
                SubmitState.Submitting -> Unit
                is SubmitState.Sent -> navController.popBackStack()
                else -> {
                    notice = null
                    step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts)
                }
            }
        }
    }

    when (val current = step) {
        AlgorandSendStep.Form -> AlgorandSendFormScreen(
            network = network,
            fromAddress = address,
            assets = assets,
            selected = selected,
            onSelect = { selectedKey = it.key },
            balanceText = balanceText(loaded, selected),
            recipient = recipient,
            onRecipient = { recipient = it },
            onScan = {
                recipientStatus = RECIPIENT_HINT
                step = AlgorandSendStep.ScanRecipient
            },
            amount = amount,
            onAmount = { amount = it },
            maxEnabled = maxUnits != null && maxUnits.signum() > 0,
            onMax = { maxUnits?.let { amount = AlgorandAssets.text(it, selected.decimals) } },
            feeText = feeEstimate?.let { AlgorandFormat.amount(it) },
            amountError = amountError,
            busy = busy || loaded == null,
            notice = notice,
            canReview = canReview,
            onReview = ::review,
            onBack = { navController.popBackStack() },
        )
        AlgorandSendStep.ScanRecipient -> ScanScreen(
            title = "Scan address",
            progress = null,
            statusText = recipientStatus,
            onText = ::onRecipientText,
            onCancel = { step = AlgorandSendStep.Form },
        )
        is AlgorandSendStep.Review -> AlgorandReviewScreen(
            plan = current.plan,
            symbol = selected.symbol,
            decimals = selected.decimals,
            notice = notice,
            onConfirm = { confirm(current.plan) },
            onBack = {
                notice = null
                step = AlgorandSendStep.Form
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
        is AlgorandSendStep.Submit -> SubmitScreen(
            machine = current.machine,
            rows = algorandSummary(current.plan, selected.symbol, selected.decimals),
            explorerUrl = { txid -> AlgorandSend.explorerUrl(current.plan.network, txid) },
            status = sentStatus,
            explorerNotice = sentNotice,
            onSubmit = { submit(current) },
            onOpenExplorer = { txid -> openExplorer(AlgorandSend.explorerUrl(current.plan.network, txid)) },
            onStartOver = {
                notice = null
                step = AlgorandSendStep.Form
            },
            onBack = {
                notice = null
                step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts)
            },
            onDone = { navController.popBackStack() },
        )
    }
}

/**
 * The assets that [address] has opted in to, zero balances included, so that an opted-in asset is never offered an opt-in. An address the network does not
 * know has opted in to none.
 */
private suspend fun optedInAssets(scheduler: Scheduler, network: NetworkId, address: String): List<AsaHolding> = try {
    scheduler.run(AsaHoldings(network, address), Priority.FOREGROUND)
} catch (e: ProviderError.NotFound) {
    emptyList()
}

/** The balance the Details show for [asset]: ALGO's balance, or the asset's held amount. Null until the network has been read, or when the asset is not held. */
private fun balanceText(funds: AlgorandFunds?, asset: AlgorandAsset): String? = when {
    funds == null -> null
    asset.assetId == null -> AlgorandFormat.amount(funds.balance)
    else -> asset.held?.let { "${AlgorandAssets.text(it, asset.decimals)} ${asset.symbol}" }
}

/** The reason [text] cannot be sent in [asset], given the most that can be: null when it can, or when nothing is typed yet. */
private fun amountError(text: String, asset: AlgorandAsset, max: BigInteger?): String? {
    if (text.isBlank()) return null
    val units = runCatching { AlgorandAssets.units(text, asset.decimals, asset.symbol) }.getOrElse { return it.message ?: "Enter an amount in ${asset.symbol}." }
    if (max != null && units > max) return "More than you can send. The most is ${AlgorandAssets.text(max, asset.decimals)} ${asset.symbol}."
    return null
}
