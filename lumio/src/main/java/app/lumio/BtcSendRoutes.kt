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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import app.airvault.core.model.AccountId
import app.lensly.DebugBtcFixture
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.VaultHolder
import app.lensly.app.addressNotesOf
import app.lensly.app.isolated
import app.lensly.app.plainLabel
import app.lensly.app.plainName
import app.lensly.core.btc.BtcNetwork
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.btc.AddressStatsCache
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcAdvanced
import app.lumio.btc.BtcPortfolio
import app.lumio.btc.BtcReadException
import app.lumio.btc.BtcSend
import app.lumio.btc.BtcSendInput
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.BtcSpendable
import app.lumio.btc.FALLBACK_FEE_RATE_SAT_PER_VB
import app.lumio.btc.btcText
import app.lumio.btc.indexerOf
import app.lumio.btc.networkId
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.FeeEstimates
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Tx
import app.lumio.data.sched.Priority
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.send.ArrivalWatch
import app.lumio.send.BtcPaymentCode
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.send.bitcoinLanding
import app.lumio.send.bitcoinOwnChoices
import app.lumio.ui.pair.PairLabels
import app.lumio.ui.send.RECIPIENT_SCAN_HINT
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendChangeRecipientDialog
import app.lumio.ui.send.SendChoice
import app.lumio.ui.send.SendDeclinedPage
import app.lumio.ui.send.SendFeeRateSheet
import app.lumio.ui.send.SendRecipientPage
import app.lumio.ui.send.SendReviewPage
import app.lumio.ui.send.SendShowPage
import app.lumio.ui.scan.ReplyReading
import app.lumio.ui.send.SignatureScanScreen
import app.lumio.ui.send.SignedSendSteps
import app.lumio.ui.send.SummaryRow
import app.lumio.ui.send.rememberSendOffline
import app.lumio.wallets.WalletAccounts
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The steps of a Bitcoin send, in the order the owner goes through them: Recipient, Amount, Review, Lensly Signs, the scan of Lensly's reply, and the
 * signed transaction. Cancel Send before the reply is scanned goes to [Declined], which sends nothing.
 */
private sealed interface BtcSendStep {
    data object Recipient : BtcSendStep

    /** The camera reads a recipient: an address, or a payment code that fills the form. */
    data object ScanRecipient : BtcSendStep

    data object Amount : BtcSendStep

    class Review(val plan: BtcSendPlan) : BtcSendStep

    class Request(val plan: BtcSendPlan, val parts: List<String>) : BtcSendStep

    class Scanning(val plan: BtcSendPlan, val parts: List<String>) : BtcSendStep

    /** The owner ended the send before Lensly's signature was scanned, so nothing reached Lumio. */
    class Declined(val plan: BtcSendPlan) : BtcSendStep

    /** The signed transaction, held in memory until the owner sends it. Leaving the page discards it. */
    class Submit(val plan: BtcSendPlan, val parts: List<String>, val machine: SubmitMachine<*, String>) : BtcSendStep
}

private const val SCAN_READ = "Signature read. Check the transaction, then send it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NETWORK_FAILED = "The Bitcoin network could not be read. Try again."
private const val NO_BROWSER = "No browser can open the explorer."
private const val TIMING = "Usually 10–60 minutes for the first confirmation"
private const val SETTLE_MS = 700L

/** The open vault's address readings of [net]. A send reads them only while the vault is open; a locked one gets an empty memo that dies with the call. */
private fun LumioApp.addressStatsOf(net: NetworkId) = (lumioVault()?.addressStats ?: AddressStatsCache()).forNetwork(net)

/** The account a Bitcoin send is from: [Missing] when the vault has no such account on the side the owner chose. */
private sealed interface BtcSendLoad {
    data object Missing : BtcSendLoad

    class Ready(val account: BtcAccountRef) : BtcSendLoad
}

/**
 * A Bitcoin send from the paired [account] of the wallet [walletId], which is fixed. The send runs on the shared Clear Air pages: the recipient, the
 * amount with the fee sheet, the review, the request that Lensly signs, the scan of its signed answer, and the signed transaction. Lumio holds no key.
 * It builds the unsigned transaction from public reads, and it broadcasts only a signed answer that carries the same transaction, with every input signed.
 */
@Composable
internal fun BtcSendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    account: AccountId,
    mode: NetworkMode,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val transactions = rememberTransactions(app)
    val context = LocalContext.current
    val offline = rememberSendOffline(app)
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

    var step by remember { mutableStateOf<BtcSendStep>(BtcSendStep.Recipient) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var feeRate by rememberSaveable { mutableStateOf("") }
    var feeSheet by remember { mutableStateOf(DebugDemoData.flag("fee:bitcoin")) }
    var changeRecipient by remember { mutableStateOf(false) }
    // Whether the owner has changed the fee field. Once they have, no suggestion replaces what they typed.
    var feeEdited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientStatus by remember { mutableStateOf(RECIPIENT_SCAN_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanReading by remember { mutableStateOf<ReplyReading?>(null) }
    var scanNotice by remember { mutableStateOf<String?>(null) }
    var scanBusy by remember { mutableStateOf(false) }
    // The server's fee estimate for the network, and the coins the send may spend. The fee sheet plans again on these.
    var suggestedRate by remember { mutableStateOf<Double?>(null) }
    var spendable by remember { mutableStateOf<BtcSpendable?>(null) }
    // The send as the amount is typed: its fee is the network's for these coins, so the Amount page can show it.
    var preview by remember { mutableStateOf<BtcSendPlan?>(null) }

    val network = selected.account.network
    val fromName = PairLabels.scriptType(selected.account.scriptType)
    val fromPath = selected.account.path.asString('\'')
    val paired = wallets.firstOrNull { it.fingerprint == selected.account.masterFingerprint }
    val addressBook = paired?.addressBook.orEmpty()

    // The coins the send may spend, read from the network, or from the pairing fixture in a debug build. A read failure keeps the page, with the words why.
    suspend fun readSpendable(): BtcSpendable = DebugSpendFixture.spendable(selected.account)
        ?: BtcSend.spendable(
            selected.account,
            app.scheduler,
            app.addressStatsOf(network.networkId()),
            indexer = app.lumioVault()?.let { indexerOf(it.configs(), app.http) },
        )

    // The fee rate is optional: the form starts at the server's estimate for about 30 minutes, or at the fallback rate while that is read or when
    // no estimate comes. A rate the owner has typed is never replaced, and a blank field means the suggestion.
    LaunchedEffect(network) {
        if (feeRate.isBlank() && !feeEdited) feeRate = BtcSend.formatFeeRate(FALLBACK_FEE_RATE_SAT_PER_VB)
        val estimates = runCatching {
            withContext(Dispatchers.IO) { app.scheduler.run(FeeEstimates(network.networkId()), Priority.FOREGROUND) }
        }.getOrNull()
        val suggestion = BtcSend.feeSuggestion(estimates)
        suggestedRate = suggestion.satPerVb
        feeRate = BtcSend.fieldAfterSuggestion(feeRate, feeEdited, suggestion.satPerVb)
    }

    // The coins are read when the Amount page opens, and the send is planned again whenever the owner changes what it moves.
    LaunchedEffect(step is BtcSendStep.Amount) {
        if (step !is BtcSendStep.Amount) return@LaunchedEffect
        if (spendable == null) {
            runCatching { withContext(Dispatchers.IO) { readSpendable() } }
                .onSuccess { spendable = it }
                .onFailure { notice = ownerWords(it, NETWORK_FAILED) }
        }
    }
    LaunchedEffect(step is BtcSendStep.Amount, recipient, amount, feeRate, spendable) {
        val pool = spendable
        preview = null
        if (step !is BtcSendStep.Amount || pool == null) return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            runCatching {
                BtcSend.plan(selected.account, pool, BtcSendInput.recipient(recipient, network), BtcSendInput.amountSats(amount), BtcSendInput.feeRate(feeRate))
            }.getOrNull()
        }
    }

    fun review() {
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val to = BtcSendInput.recipient(recipient, network)
                    val sats = BtcSendInput.amountSats(amount)
                    val rate = BtcSendInput.feeRate(feeRate.ifBlank { BtcSend.formatFeeRate(suggestedRate ?: FALLBACK_FEE_RATE_SAT_PER_VB) })
                    val pool = spendable ?: readSpendable()
                    pool to BtcSend.plan(selected.account, pool, to, sats, rate)
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

    /** Use Max fills the amount with every coin less the fee at the rate in the field. The owner's amount is replaced, and a refusal shows its words. */
    fun useMax() {
        val pool = spendable ?: return
        runCatching {
            BtcSend.maxAmountSats(selected.account, pool, BtcSendInput.recipient(recipient, network), BtcSendInput.feeRate(feeRate))
        }
            .onSuccess {
                amount = BtcSend.formatAmount(it)
                notice = null
            }
            .onFailure { error -> notice = ownerWords(error, PREPARE_FAILED) }
    }

    /** A scanned recipient fills To, and the amount when the form has none. A code that is refused keeps the camera on, with the words why. */
    fun onRecipientText(text: String) {
        runCatching { BtcPaymentCode.read(text, network) }
            .onSuccess { payment ->
                recipient = payment.address
                if (payment.amount != null && amount.isBlank()) amount = payment.amount
                notice = null
                step = BtcSendStep.Recipient
            }
            .onFailure { error -> recipientStatus = ownerWords(error, RECIPIENT_SCAN_HINT) }
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
        scanReading = null
        scanNotice = null
        notice = null
        step = BtcSendStep.Scanning(current.plan, current.parts)
    }

    /** Checks Lensly's signature and holds the signed transaction. Nothing is sent until the owner sends it on the next page. */
    fun applySignature(current: BtcSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanNotice = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { BtcSend.signed(current.plan, ur) } }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanReading = null
                    scanNotice = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { tx ->
                    val planNetwork = current.plan.network
                    val machine = SubmitMachine(
                        signed = tx,
                        broadcast = { signedTx ->
                            withContext(Dispatchers.IO) {
                                BtcSend.broadcast(app.scheduler, planNetwork, signedTx)
                                    .also { BtcSend.reserveChange(current.plan, app.addressStatsOf(planNetwork.networkId())) }
                            }
                        },
                        describe = { error -> SubmitFailure(ownerWords(error, BROADCAST_FAILED)) },
                    )
                    notice = null
                    step = BtcSendStep.Submit(current.plan, current.parts, machine)
                },
            )
        }
    }

    fun onSignatureText(current: BtcSendStep.Scanning, text: String) {
        if (scanBusy) return
        when (val result = scan.onText(text)) {
            is SignatureStep.Reading -> {
                scanReading = ReplyReading(result.received, result.expected)
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
            Toast.makeText(context, NO_BROWSER, Toast.LENGTH_SHORT).show()
        }
    }

    val backLabel = navController.backLabel()
    BackHandler {
        when (val current = step) {
            BtcSendStep.Recipient -> navController.popBackStack()
            BtcSendStep.ScanRecipient -> step = BtcSendStep.Recipient
            BtcSendStep.Amount -> step = BtcSendStep.Recipient
            is BtcSendStep.Review -> {
                notice = null
                step = BtcSendStep.Amount
            }
            is BtcSendStep.Request -> {
                notice = null
                step = BtcSendStep.Review(current.plan)
            }
            is BtcSendStep.Scanning -> step = BtcSendStep.Request(current.plan, current.parts)
            is BtcSendStep.Declined -> navController.popBackStack()
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
        BtcSendStep.Recipient -> {
            // The error waits until the owner pauses, so it never shows while the first characters are typed.
            var settled by remember { mutableStateOf("") }
            LaunchedEffect(recipient) {
                delay(SETTLE_MS)
                settled = recipient
            }
            val invalid = settled.isNotBlank() && btcRecipientError(settled, network) != null
            SendRecipientPage(
                value = recipient,
                onValueChange = {
                    recipient = it.trim()
                    notice = null
                },
                error = if (invalid) btcRecipientError(settled, network) else null,
                hint = null,
                canContinue = recipient.isNotBlank() && btcRecipientError(recipient, network) == null,
                accounts = paired?.let { bitcoinOwnChoices(it, network, selected.account.receiveAddress(0)) }.orEmpty(),
                addressBook = addressBook
                    .filter { btcRecipientError(it.address, network) == null }
                    .map { entry -> SendChoice(title = entry.plainName(), detail = entry.plainLabel(), address = entry.address) },
                onPick = { choice -> recipient = choice.address },
                onScan = {
                    recipientStatus = RECIPIENT_SCAN_HINT
                    step = BtcSendStep.ScanRecipient
                },
                onContinue = { step = BtcSendStep.Amount },
                backLabel = backLabel,
                offline = offline,
                onBack = { navController.popBackStack() },
                onLock = onLock,
            )
        }
        BtcSendStep.ScanRecipient -> RecipientScanScreen(
            status = recipientStatus,
            onText = ::onRecipientText,
            onCancel = { step = BtcSendStep.Recipient },
        )
        BtcSendStep.Amount -> {
            val pool = spendable
            val total = pool?.coins?.sumOf { it.valueSats }
            val notes = remember(recipient, addressBook) { addressNotesOf(recipient, BITCOIN_FAMILY, emptyMap(), addressBook) }
            val toName = nameOrAddress(notes, recipient)
            val sent = preview
            SendAmountPage(
                symbol = "BTC",
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = recipient,
                fromTitle = fromName,
                fromDetail = total?.let { "${btcText(it)} available" } ?: "Reading the network",
                asset = null,
                onAsset = null,
                amount = amount,
                onAmount = { amount = it },
                amountError = BtcSendInput.amountError(amount, total) ?: notice,
                leaves = if (sent != null && total != null && total - sent.amountSats - sent.feeSats >= 0) {
                    "Leaves ${btcText(total - sent.amountSats - sent.feeSats)} after the fee"
                } else {
                    null
                },
                onUseMax = if (total != null) ::useMax else null,
                networkName = btcNetworkName(network),
                feeText = sent?.let { btcText(it.feeSats) } ?: "Reading the network",
                onFee = if (sent != null) { { feeSheet = true } } else null,
                note = null,
                canReview = sent != null && !busy,
                onReview = ::review,
                onChangeRecipient = { changeRecipient = true },
                backLabel = backLabel,
                offline = offline,
                onBack = { step = BtcSendStep.Recipient },
                onLock = onLock,
            )
            if (sent != null) {
                SendFeeRateSheet(
                    visible = feeSheet,
                    suggestedRate = suggestedRate ?: sent.feeRateSatPerVb,
                    rate = sent.feeRateSatPerVb,
                    networkFee = btcText(sent.feeSats),
                    timing = TIMING,
                    onApply = { text ->
                        feeRate = text
                        feeEdited = true
                    },
                    onClose = { feeSheet = false },
                )
            }
            if (changeRecipient) {
                SendChangeRecipientDialog(
                    keepLabel = toName,
                    onChange = {
                        changeRecipient = false
                        recipient = ""
                        step = BtcSendStep.Recipient
                    },
                    onKeep = { changeRecipient = false },
                )
            }
        }
        is BtcSendStep.Review -> {
            val notes = remember(current.plan.recipient, addressBook) {
                addressNotesOf(current.plan.recipient, BITCOIN_FAMILY, emptyMap(), addressBook)
            }
            val toName = nameOrAddress(notes, current.plan.recipient)
            val warnings = lookalikeWarnings(notes) + BtcAdvanced.warnings(suggestedRate ?: current.plan.feeRateSatPerVb, current.plan.feeRateSatPerVb)
            SendReviewPage(
                heading = "Send ${btcText(current.plan.amountSats)} from ${isolated(fromName)} to ${nameWithEnds(notes, current.plan.recipient)}?",
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = current.plan.recipient,
                validLine = "Address is valid on ${btcNetworkName(network)}",
                firstTime = if (isFirstTime(notes)) "First time sending here. Compare every group with where you got it." else null,
                rows = btcRows(current.plan, fromName, fromPath),
                warnings = warnings,
                notice = notice,
                onShow = { confirm(current.plan) },
                onEdit = {
                    notice = null
                    step = BtcSendStep.Amount
                },
                backLabel = backLabel,
                offline = offline,
                onBack = {
                    notice = null
                    step = BtcSendStep.Amount
                },
                onLock = onLock,
            )
        }
        is BtcSendStep.Request -> SendShowPage(
            parts = current.parts,
            onNext = { startScan(current) },
            onCancel = { step = BtcSendStep.Declined(current.plan) },
            backLabel = backLabel,
            offline = offline,
            onBack = {
                notice = null
                step = BtcSendStep.Review(current.plan)
            },
            onLock = onLock,
        )
        is BtcSendStep.Scanning -> SignatureScanScreen(
            reading = scanReading,
            notice = scanNotice,
            onText = { text -> onSignatureText(current, text) },
            onBack = { step = BtcSendStep.Request(current.plan, current.parts) },
            onCancel = { step = BtcSendStep.Declined(current.plan) },
            backLabel = backLabel,
            onLock = onLock,
        )
        is BtcSendStep.Declined -> SendDeclinedPage(
            amount = btcText(current.plan.amountSats),
            fromName = fromName,
            backLabel = backLabel,
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
        is BtcSendStep.Submit -> {
            val toName = nameWithAddress(addressNotesOf(current.plan.recipient, BITCOIN_FAMILY, emptyMap(), addressBook), current.plan.recipient)
            SignedSendSteps(
                machine = current.machine,
                headline = "Your BTC Is on Its Way",
                amount = btcText(current.plan.amountSats),
                recipient = toName,
                network = btcNetworkName(network),
                timing = TIMING,
                fromName = fromName,
                arrival = ArrivalWatch(
                    arrivals = app.sendArrivals,
                    network = current.plan.network.networkId(),
                    arrivedHeadline = "Your BTC Has Arrived",
                    read = { txid -> bitcoinLanding(app.scheduler.run(Tx(current.plan.network.networkId(), txid), Priority.BACKGROUND)) },
                ),
                explorerUrl = { txid -> BtcPortfolio.explorerTxUrl(current.plan.network, txid) },
                onExplorer = { txid -> openExplorer(BtcPortfolio.explorerTxUrl(current.plan.network, txid)) },
                onSent = {},
                onStop = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
                onReview = {
                    notice = null
                    step = BtcSendStep.Review(current.plan)
                },
                lastChanceSeconds = transactions.lastChanceSeconds,
                backLabel = backLabel,
                offline = offline,
                onLock = onLock,
            )
        }
    }
}

/** The name of a Bitcoin network on the send pages. */
private fun btcNetworkName(network: BtcNetwork): String = if (network == BtcNetwork.TESTNET4) "Bitcoin Testnet4" else "Bitcoin Mainnet"

/** The rows under the recipient on the Review page: the amount's fee, the inputs the send spends, the change when there is some, the network, and the account. */
private fun btcRows(plan: BtcSendPlan, fromName: String, fromPath: String): List<SummaryRow> = buildList {
    add(SummaryRow("Amount", btcText(plan.amountSats)))
    add(SummaryRow("Fee", btcText(plan.feeSats)))
    add(SummaryRow("Spends", "${plan.coins.size} ${if (plan.coins.size == 1) "Input" else "Inputs"}"))
    if (plan.changeSats > 0) add(SummaryRow("Change", btcText(plan.changeSats)))
    add(SummaryRow("Network", btcNetworkName(plan.network)))
    add(SummaryRow("From", "$fromName, $fromPath"))
}

/** The reason [text] is not a Bitcoin address on [network], or null when it is one. */
internal fun btcRecipientError(text: String, network: BtcNetwork): String? = runCatching { BtcSendInput.recipient(text, network) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

/** The words a failure shows: the owner's words for a refusal, a server's refusal, or a failed network read, else [fallback]. */
private fun ownerWords(error: Throwable, fallback: String): String = when (error) {
    is IllegalArgumentException -> error.message ?: fallback
    // A read names the step that failed, so the owner sees what Lumio was reading and why.
    is BtcReadException -> error.message ?: fallback
    is ProviderError.Fatal -> error.message ?: fallback
    is ProviderError -> NETWORK_FAILED
    else -> fallback
}
