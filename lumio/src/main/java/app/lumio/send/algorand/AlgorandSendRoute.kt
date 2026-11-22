package app.lumio.send.algorand

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import app.airvault.core.model.AccountId
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.VaultHolder
import app.lensly.app.addressNotesOf
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.LumioApp
import app.lumio.backLabel
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
import app.lumio.notesDetail
import app.lumio.notesName
import app.lumio.onVault
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.send.ArrivalWatch
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.send.algorandLanding
import app.lumio.send.algorandOwnChoices
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendChangeRecipientDialog
import app.lumio.ui.send.SendChoice
import app.lumio.ui.send.SendDeclinedPage
import app.lumio.ui.send.SendOption
import app.lumio.ui.send.SendOptionSheet
import app.lumio.ui.send.SendReadingPage
import app.lumio.ui.send.SendRecipientPage
import app.lumio.ui.send.SendReviewPage
import app.lumio.ui.send.SendShowPage
import app.lumio.ui.send.SignatureScanScreen
import app.lumio.ui.send.SignedSendSteps
import app.lumio.ui.send.SummaryRow
import app.lumio.ui.send.rememberSendOffline
import app.lumio.wallets.WalletAccounts
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The steps of an Algorand send, in the order the owner goes through them: Recipient, Amount, Review, Lensly Signs, the scan of Lensly's reply, and the
 * signed transaction. An opt-in starts at [Reading], while its asset is read, and then opens its review. Cancel Send before the reply is scanned goes to
 * [Declined], which sends nothing.
 */
private sealed interface AlgorandSendStep {
    data object Recipient : AlgorandSendStep

    /** An opt-in while its asset is read from the network, before its review opens. */
    data object Reading : AlgorandSendStep

    /** The camera reads a recipient address. */
    data object ScanRecipient : AlgorandSendStep

    data object Amount : AlgorandSendStep

    class Review(val plan: AlgorandSendPlan) : AlgorandSendStep

    class Request(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>) : AlgorandSendStep

    class Scanning(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>) : AlgorandSendStep

    /** The owner ended the send before Lensly's signature was scanned, so nothing reached Lumio. */
    class Declined(val plan: AlgorandSendPlan) : AlgorandSendStep

    /** The signed transaction, held in memory until the owner sends it. Leaving the page discards it. */
    class Submit(val plan: AlgorandSendPlan, val requestId: ByteArray, val parts: List<String>, val machine: SubmitMachine<*, String>) : AlgorandSendStep
}

private const val RECIPIENT_HINT = "Point the camera at an Algorand address."
private const val RECIPIENT_REFUSED = "That code is not an Algorand address. Point the camera at an address."
private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_READ = "Signature read. Check the transaction, then send it."
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
private const val SETTLE_MS = 700L

/**
 * An Algorand send from one account of a paired wallet: the recipient, the amount, the review, the request that Lensly signs, the scan of its signature,
 * and the transaction that is sent, whose status is read from the pool after it. The account is fixed by the caller, and the network is the side the
 * owner chose. The coins are ALGO and the assets the account holds. With [optInAssetId] the route opens the opt-in of that asset instead: its review
 * comes first, and the signing and the sending are the same. Lumio holds no key.
 */
@Composable
internal fun AlgorandSendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    mode: NetworkMode,
    walletId: String,
    account: AccountId,
    onLock: () -> Unit,
    optInAssetId: ULong? = null,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val offline = rememberSendOffline(app)
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
    val accountIndex = WalletAccounts.indexOf(wallet, account) ?: return
    val signer = wallet.algorand[accountIndex]
    val address = AlgorandAccount.addressOf(signer)
    val network = if (mode.holds(ALGORAND_TESTNET.testnet)) ALGORAND_TESTNET else ALGORAND_MAINNET
    val networkLabel = if (network.testnet) "Algorand Testnet" else "Algorand Mainnet"
    val fromName = "Account ${accountIndex + 1}"
    val scheduler = app.nativeSchedulerFor(session)

    var step by remember { mutableStateOf<AlgorandSendStep>(if (optInAssetId == null) AlgorandSendStep.Recipient else AlgorandSendStep.Reading) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    // The coin the send moves, by its key: ALGO, or the ID of an asset. It is saved, so that a rotation keeps the owner's choice.
    var selectedKey by rememberSaveable { mutableStateOf(AlgorandAssets.ALGO_KEY) }
    var assets by remember { mutableStateOf(listOf(AlgorandAssets.native)) }
    var assetSheet by remember { mutableStateOf(false) }
    var changeRecipient by remember { mutableStateOf(false) }
    var funds by remember { mutableStateOf<AlgorandFunds?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientStatus by remember { mutableStateOf(RECIPIENT_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentStatus by remember { mutableStateOf(PENDING_TEXT) }
    var sentTxid by remember { mutableStateOf<String?>(null) }

    val selected = assets.firstOrNull { it.key == selectedKey } ?: AlgorandAssets.native
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
        isAlgo -> feeEstimate?.let { AlgorandSend.maxMicro(loaded, it) }
        else -> selected.held
    }
    val amountError = amountError(amount, selected, maxUnits)
    val canReview = loaded != null && !busy && to != null && amount.isNotBlank() && amountError == null

    // The balance, the parameters and the coins are read when the recipient page opens. A failed read keeps the page, with the words why.
    LaunchedEffect(step is AlgorandSendStep.Recipient) {
        if (step !is AlgorandSendStep.Recipient) return@LaunchedEffect
        funds = null
        runCatching {
            withContext(Dispatchers.IO) {
                val read = AlgorandSend.funds(scheduler, network, address)
                val infos = AlgorandAssets.infoIds(read.holdings).associateWith { ChainHoldings.info(scheduler, network, it) }
                read to AlgorandAssets.choices(read.holdings, infos)
            }
        }
            .onSuccess { (read, choices) ->
                funds = read
                assets = choices
            }
            .onFailure { notice = AlgorandSend.ownerWords(it, READ_FAILED) }
    }

    // An opt-in reads the balance and the asset's name and decimals, then opens its review. A failed read keeps the screen, with the words why.
    LaunchedEffect(optInAssetId) {
        val id = optInAssetId ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                val read = AlgorandSend.funds(scheduler, network, address)
                val info = ChainHoldings.info(scheduler, network, id)
                val asset = AlgorandAsset(id, info.unitName.ifEmpty { "ASA $id" }, info.decimals, held = null)
                val plan = AlgorandAssets.plan(asset, network, read, wallet.fingerprint, accountIndex, signer, signer, BigInteger.ZERO, recipientOptedIn = false)
                Triple(read, asset, plan)
            }
        }
            .onSuccess { (read, asset, plan) ->
                funds = read
                assets = listOf(AlgorandAssets.native, asset)
                selectedKey = asset.key
                notice = null
                step = AlgorandSendStep.Review(plan)
            }
            .onFailure { notice = AlgorandSend.ownerWords(it, PREPARE_FAILED) }
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

    /** Back to the start of the flow: the recipient of a send, or the Add asset page that an opt-in opened from. */
    fun backToStart() {
        notice = null
        if (optInAssetId == null) step = AlgorandSendStep.Amount else navController.popBackStack()
    }

    /**
     * The plan of the send of a held coin: a payment of ALGO, or a transfer of the asset. The plan is made from the funds read now, not from
     * the read the recipient page took when it opened, so a form left open past the validity window does not plan a transaction the network refuses.
     */
    fun review() {
        if (loaded == null) return
        val asset = selected
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { reviewPlan(scheduler, network, address, asset, recipient, amount, wallet.fingerprint, accountIndex, signer) }
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
            Toast.makeText(context, NO_BROWSER, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        when (val current = step) {
            AlgorandSendStep.Recipient, AlgorandSendStep.Reading -> navController.popBackStack()
            AlgorandSendStep.ScanRecipient -> step = AlgorandSendStep.Recipient
            AlgorandSendStep.Amount -> step = AlgorandSendStep.Recipient
            is AlgorandSendStep.Review -> backToStart()
            is AlgorandSendStep.Request -> {
                notice = null
                step = AlgorandSendStep.Review(current.plan)
            }
            is AlgorandSendStep.Scanning -> step = AlgorandSendStep.Request(current.plan, current.requestId, current.parts)
            is AlgorandSendStep.Declined -> navController.popBackStack()
            is AlgorandSendStep.Submit -> when (current.machine.state.value) {
                SubmitState.Submitting -> Unit
                is SubmitState.Sent -> navController.popBackStack()
                else -> {
                    notice = null
                    step = AlgorandSendStep.Review(current.plan)
                }
            }
        }
    }

    val backLabel = navController.backLabel()
    when (val current = step) {
        AlgorandSendStep.Reading -> SendReadingPage(
            title = "Opt In to Asset",
            notice = notice,
            backLabel = backLabel,
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
        AlgorandSendStep.Recipient -> {
            // The error waits until the owner pauses, so it never shows while the first characters are typed.
            var settled by remember { mutableStateOf("") }
            LaunchedEffect(recipient) {
                delay(SETTLE_MS)
                settled = recipient
            }
            val invalid = settled.isNotBlank() && AlgorandSend.recipientError(settled) != null
            SendRecipientPage(
                value = recipient,
                onValueChange = {
                    recipient = it.trim()
                    notice = null
                },
                error = if (invalid) AlgorandSend.recipientError(settled) else null,
                hint = null,
                canContinue = recipient.isNotBlank() && AlgorandSend.recipientError(recipient) == null,
                accounts = algorandOwnChoices(wallet, address),
                addressBook = wallet.addressBook
                    .filter { AlgorandSend.recipientError(it.address) == null }
                    .map { entry -> SendChoice(title = entry.name, detail = entry.label ?: "Address Book", address = entry.address) },
                onPick = { choice -> recipient = choice.address },
                onPaste = { clipboard.getText()?.text?.trim()?.let { recipient = it } },
                onScan = {
                    recipientStatus = RECIPIENT_HINT
                    step = AlgorandSendStep.ScanRecipient
                },
                onContinue = { step = AlgorandSendStep.Amount },
                backLabel = backLabel,
                offline = offline,
                onBack = { navController.popBackStack() },
                onLock = onLock,
            )
        }
        AlgorandSendStep.ScanRecipient -> RecipientScanScreen(
            status = recipientStatus,
            onText = ::onRecipientText,
            onCancel = { step = AlgorandSendStep.Recipient },
        )
        AlgorandSendStep.Amount -> {
            val notes = addressNotesOf(recipient, ALGORAND_FAMILY, emptyMap(), wallet.addressBook)
            val toName = notes?.let(::notesName) ?: groupAddress(recipient)
            val fee = feeEstimate
            val leaves = if (loaded != null && isAlgo && fee != null) {
                runCatching { AlgorandAssets.units(amount, selected.decimals, selected.symbol) }.getOrNull()
                    ?.let { typed -> (loaded.balance - typed - fee).takeIf { it.signum() >= 0 } }
                    ?.let { "Leaves ${AlgorandFormat.amount(it)} after the fee" }
            } else {
                null
            }
            val keep = loaded?.let { AlgorandSend.algoText(it.minimum) }
            SendAmountPage(
                symbol = selected.symbol,
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = recipient,
                fromTitle = fromName,
                fromDetail = balanceText(loaded, selected) ?: "Reading the network",
                asset = selected.symbol.takeIf { assets.size > 1 },
                onAsset = if (assets.size > 1) { { assetSheet = true } } else null,
                amount = amount,
                onAmount = { amount = it },
                amountError = amountError ?: notice,
                leaves = leaves,
                onUseMax = if (maxUnits != null && maxUnits.signum() > 0) {
                    { amount = AlgorandAssets.text(maxUnits, selected.decimals) }
                } else {
                    null
                },
                networkName = networkLabel,
                feeText = if (isAlgo) fee?.let { AlgorandFormat.amount(it) } ?: "Reading the network" else "Shown on review",
                onFee = null,
                note = keep?.let { "$it ALGO stays reserved. That part is never sent." },
                canReview = canReview,
                onReview = ::review,
                onChangeRecipient = { changeRecipient = true },
                backLabel = backLabel,
                offline = offline,
                onBack = { step = AlgorandSendStep.Recipient },
                onLock = onLock,
            )
            if (assets.size > 1) {
                SendOptionSheet(
                    visible = assetSheet,
                    heading = "Asset",
                    options = assets.map { SendOption(title = it.symbol, detail = assetDetail(it)) },
                    onPick = { index ->
                        selectedKey = assets[index].key
                        assetSheet = false
                    },
                    onClose = { assetSheet = false },
                )
            }
            if (changeRecipient) {
                SendChangeRecipientDialog(
                    keepLabel = toName,
                    onChange = {
                        changeRecipient = false
                        recipient = ""
                        step = AlgorandSendStep.Recipient
                    },
                    onKeep = { changeRecipient = false },
                )
            }
        }
        is AlgorandSendStep.Review -> {
            val optIn = current.plan is AsaSendPlan && current.plan.isOptIn
            val notes = if (optIn) null else addressNotesOf(current.plan.toAddress, ALGORAND_FAMILY, emptyMap(), wallet.addressBook)
            val toName = if (optIn) fromName else notes?.let(::notesName) ?: groupAddress(current.plan.toAddress)
            val toAddress = if (optIn) current.plan.fromAddress else current.plan.toAddress
            SendReviewPage(
                heading = if (optIn) {
                    "Opt In to ${selected.symbol} (ASA ${selected.assetId})?"
                } else {
                    "Send ${AlgorandAssets.amountText(current.plan, selected.symbol, selected.decimals)} from $fromName to $toName?"
                },
                toTitle = toName,
                toDetail = if (optIn) "Your account" else notes?.let(::notesDetail).orEmpty(),
                toAddress = toAddress,
                validLine = "Address is valid on $networkLabel",
                firstTime = if (!optIn && notes == null) "First time sending here. Compare every group with where you got it." else null,
                rows = algorandRows(current.plan, selected.symbol, selected.decimals, networkLabel),
                warnings = emptyList(),
                notice = notice,
                onShow = { confirm(current.plan) },
                onEdit = { backToStart() },
                backLabel = backLabel,
                offline = offline,
                onBack = { backToStart() },
                onLock = onLock,
            )
        }
        is AlgorandSendStep.Request -> SendShowPage(
            parts = current.parts,
            onNext = { startScan(current) },
            onCancel = { step = AlgorandSendStep.Declined(current.plan) },
            backLabel = backLabel,
            offline = offline,
            onBack = {
                notice = null
                step = AlgorandSendStep.Review(current.plan)
            },
            onLock = onLock,
        )
        is AlgorandSendStep.Scanning -> SignatureScanScreen(
            progress = scanProgress,
            status = scanStatus,
            onText = { text -> onSignatureText(current, text) },
            onCancel = { step = AlgorandSendStep.Declined(current.plan) },
        )
        is AlgorandSendStep.Declined -> SendDeclinedPage(
            amount = AlgorandAssets.amountText(current.plan, selected.symbol, selected.decimals),
            fromName = fromName,
            backLabel = backLabel,
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
        is AlgorandSendStep.Submit -> SignedSendSteps(
            machine = current.machine,
            headline = "Your ${selected.symbol} Is on Its Way",
            amount = AlgorandAssets.amountText(current.plan, selected.symbol, selected.decimals),
            recipient = if (current.plan is AsaSendPlan && current.plan.isOptIn) fromName else groupAddress(current.plan.toAddress),
            network = networkLabel,
            timing = sentStatus,
            fromName = fromName,
            arrival = if (current.plan is AsaSendPlan && current.plan.isOptIn) null else ArrivalWatch(
                arrivals = app.sendArrivals,
                network = current.plan.network,
                arrivedHeadline = "Your ${selected.symbol} Has Arrived",
                read = { txid -> algorandLanding(AlgorandSend.pendingState(scheduler, current.plan.network, txid)) },
            ),
            explorerUrl = { txid -> AlgorandSend.explorerUrl(current.plan.network, txid) },
            onExplorer = { txid -> openExplorer(AlgorandSend.explorerUrl(current.plan.network, txid)) },
            onSent = { txid ->
                sentStatus = PENDING_TEXT
                sentTxid = txid
            },
            onStop = { navController.popBackStack() },
            onDone = { navController.popBackStack() },
            onReview = {
                notice = null
                step = AlgorandSendStep.Review(current.plan)
            },
            backLabel = backLabel,
            offline = offline,
            onLock = onLock,
        )
    }
}

/**
 * The assets that [address] has opted in to, zero balances included, so that an opted-in asset is never offered an opt-in. An address the network does not
 * know has opted in to none.
 */
internal suspend fun optedInAssets(scheduler: Scheduler, network: NetworkId, address: String): List<AsaHolding> = try {
    scheduler.run(AsaHoldings(network, address), Priority.FOREGROUND)
} catch (e: ProviderError.NotFound) {
    emptyList()
}

/**
 * The plan of a send of [asset] to [recipient] for [amount], from the funds of [address] read now: the balance, the parameters of the next
 * transaction and the recipient's opt-in. Nothing is taken from the form's read, so the validity window and the balance checks use the network
 * as it is at review.
 * @throws IllegalArgumentException when the recipient or the amount is not valid, or the balance cannot cover the send.
 */
internal suspend fun reviewPlan(
    scheduler: Scheduler,
    network: NetworkId,
    address: String,
    asset: AlgorandAsset,
    recipient: String,
    amount: String,
    fingerprint: Long,
    accountIndex: Int,
    signer: ByteArray,
): AlgorandSendPlan {
    val key = AlgorandSend.recipientKey(recipient)
    val units = AlgorandAssets.units(amount, asset.decimals, asset.symbol)
    val funds = AlgorandSend.funds(scheduler, network, address)
    val recipientOptedIn = asset.assetId?.let { id ->
        AlgorandAssets.optedIn(id, optedInAssets(scheduler, network, AlgorandAccount.addressOf(key)))
    } ?: true
    return AlgorandAssets.plan(asset, network, funds, fingerprint, accountIndex, signer, key, units, recipientOptedIn)
}

/** The rows under the recipient on the Review page: the amount, or the asset of an opt-in, then the fee, the network and the sender. */
private fun algorandRows(plan: AlgorandSendPlan, symbol: String, decimals: Int, networkLabel: String): List<SummaryRow> = buildList {
    if (plan is AsaSendPlan && plan.isOptIn) {
        add(SummaryRow("Asset", symbol))
    } else {
        add(SummaryRow("Amount", AlgorandAssets.amountText(plan, symbol, decimals)))
    }
    add(SummaryRow("Network Fee", AlgorandFormat.amount(plan.fee)))
    add(SummaryRow("Network", networkLabel))
    add(SummaryRow("From", groupAddress(plan.fromAddress)))
}

/** The balance the Amount page shows for [asset]: ALGO's balance, or the asset's held amount. Null until the network has been read, or when the asset is not held. */
private fun balanceText(funds: AlgorandFunds?, asset: AlgorandAsset): String? = when {
    funds == null -> null
    asset.assetId == null -> "${AlgorandFormat.amount(funds.balance)} available"
    else -> asset.held?.let { "${AlgorandAssets.text(it, asset.decimals)} ${asset.symbol} available" }
}

/** The balance an asset row of the Amount page shows, or that it is the native coin. */
private fun assetDetail(asset: AlgorandAsset): String = when (val held = asset.held) {
    null -> if (asset.assetId == null) "Native Coin" else "No balance on this address"
    else -> "Balance ${AlgorandAssets.text(held, asset.decimals)} ${asset.symbol}"
}

/** The reason [text] cannot be sent in [asset], given the most that can be: null when it can, or when nothing is typed yet. */
private fun amountError(text: String, asset: AlgorandAsset, max: BigInteger?): String? {
    if (text.isBlank()) return null
    val units = runCatching { AlgorandAssets.units(text, asset.decimals, asset.symbol) }.getOrElse { return it.message ?: "Enter an amount in ${asset.symbol}." }
    if (max != null && units > max) return "More than you can send. The most is ${AlgorandAssets.text(max, asset.decimals)} ${asset.symbol}."
    return null
}
