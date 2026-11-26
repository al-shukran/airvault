package app.lumio.send.solana

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
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import app.airvault.core.model.AccountId
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.VaultHolder
import app.lensly.app.addressNotesOf
import app.lensly.app.isolated
import app.lensly.app.plainLabel
import app.lensly.app.plainName
import app.lensly.core.solana.SolanaNonce
import app.lensly.core.solana.SolanaTokenAccounts
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.LumioApp
import app.lumio.backLabel
import app.lumio.chains.ChainHoldings
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.RentExemptMinimum
import app.lumio.data.provider.SolanaAccountRead
import app.lumio.data.provider.SplTokenBalances
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.sched.Priority
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.network.NetworkMode
import app.lumio.notesDetail
import app.lumio.isFirstTime
import app.lumio.lookalikeWarnings
import app.lumio.nameOrAddress
import app.lumio.nameWithEnds
import app.lumio.onVault
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.rememberTransactions
import app.lumio.send.ArrivalWatch
import app.lumio.send.Landing
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.send.solanaOwnChoices
import app.lumio.ui.send.RECIPIENT_SCAN_HINT
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendChangeRecipientDialog
import app.lumio.ui.send.SendChoice
import app.lumio.ui.send.SendDeclinedPage
import app.lumio.ui.send.SendOption
import app.lumio.ui.send.SendOptionSheet
import app.lumio.ui.send.SendRecipientPage
import app.lumio.ui.send.SendReviewPage
import app.lumio.ui.send.SendShowPage
import app.lumio.ui.scan.ReplyReading
import app.lumio.ui.send.SignatureScanScreen
import app.lumio.ui.send.SignedSendSteps
import app.lumio.ui.send.SolanaFeeSheet
import app.lumio.ui.send.SummaryRow
import app.lumio.ui.send.rememberSendOffline
import app.lumio.wallets.WalletAccounts
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The steps of a Solana send, in the order the owner goes through them. */
internal sealed interface SolanaSendStep {
    data object Recipient : SolanaSendStep

    /** The camera reads a recipient: an address, or a `solana:` link that holds one. */
    data object ScanRecipient : SolanaSendStep

    data object Amount : SolanaSendStep

    /** The plan of the send. [newTokenAccountRent] is the rent the recipient's new token account takes, or 0 when the account exists already. */
    class Review(val plan: SolanaSendPlan, val newTokenAccountRent: Long = 0L) : SolanaSendStep

    /** The one-time setup of offline signing: the nonce account to create, and the rent paid into it. Shown before Lensly signs the setup. */
    class SetupReview(val nonceAccount: String, val rentLamports: Long) : SolanaSendStep

    /**
     * A request for Lensly to sign, as shown. [plan] and [parts] stay as they are while it is on screen: Lensly's answer is checked against the request ID
     * of [plan], so a request that changed under the owner would refuse the answer to the one they scanned. [setup] is true for the one-time setup.
     */
    class Request(val plan: SolanaSendPlan, val parts: List<String>, val setup: Boolean = false) : SolanaSendStep

    class Scanning(val plan: SolanaSendPlan, val parts: List<String>, val setup: Boolean = false) : SolanaSendStep

    /** The owner ended the send before Lensly's signature was scanned, so nothing reached Lumio. */
    class Declined(val plan: SolanaSendPlan) : SolanaSendStep

    /** The signed transaction, held in memory until the owner submits it. Leaving the screen discards it. [setup] is true for the one-time setup. */
    class Submit(
        val plan: SolanaSendPlan,
        val parts: List<String>,
        val machine: SubmitMachine<*, String>,
        val setup: Boolean = false,
    ) : SolanaSendStep
}

private const val RECIPIENT_HINT = "Point the camera at a Solana address or a solana: link."
private const val SCAN_READ = "Signature read. Check the transaction, then send it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NETWORK_FAILED = "The Solana network could not be read. Try again."
private const val NO_BROWSER = "No browser can open the explorer."
internal const val SETUP_SIGN_TEXT = "One-time setup. Sign and scan back within about a minute."
private const val SETUP_DONE = "Offline signing is set up. Review the send again to sign it."
private const val SETUP_PENDING = "The setup is not confirmed yet. Review the send again in a minute."
private const val SETUP_FAILED = "The setup did not go through. Start it again from the send."
private const val SETTLE_MS = 700L
private const val COIN_READING = "Reading the coin to send."
private const val COIN_NOT_OFFERED = "This token cannot be sent from here."

/**
 * A Solana send from one paired account, which is fixed: the recipient, the amount, the review, the request that Lensly signs, the scan of its answer, and
 * the transaction that is sent, whose status is read after it. [walletId] and [accountId] name the account of the wallet, and [mode] picks the devnet or
 * mainnet-beta side. The coin is SOL, or an SPL token of the account, and the send starts on the coin with the key [startKey]: it holds the recipient page
 * until that coin is offered, so it never starts on another coin. Lumio holds no key. It builds the transfer from public reads, and it broadcasts only
 * a signature that Lensly made for that message, after the signature has been checked against the account's public key.
 */
@Composable
internal fun SolanaSendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    accountId: AccountId,
    mode: NetworkMode,
    onLock: () -> Unit,
    startKey: String = SolanaAssets.SOL_KEY,
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
    val scheduler = remember(session) { app.nativeSchedulerFor(session) }
    val network = if (mode == NetworkMode.TESTNET) SOLANA_DEVNET else SOLANA_MAINNET
    // The account N of the wallet, from the public key the pairing holds. Its private key stays with Lensly.
    val account by produceState<SolanaSendAccount?>(initialValue = null, session, walletId, accountId) {
        value = onVault(null) {
            PairedWallets.list(session).firstOrNull { it.id == walletId }?.let { wallet ->
                WalletAccounts.indexOf(wallet, accountId)?.let { index ->
                    SolanaSendAccount(index, wallet.solana[index], wallet.fingerprint.toInt())
                }
            }
        }
    }
    // The paired wallet, whose accounts and Address Book the recipient page offers and whose Address Book the send names its recipients from.
    val paired by produceState<PairedWallet?>(initialValue = null, session, walletId) {
        value = onVault(null) { PairedWallets.list(session).firstOrNull { it.id == walletId } }
    }
    val addressBook = paired?.addressBook.orEmpty()

    var step by remember { mutableStateOf<SolanaSendStep>(SolanaSendStep.Recipient) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    // The coin the send moves, by its key: SOL, or the mint of a token. It is saved, so that a rotation keeps the owner's choice.
    var selectedKey by rememberSaveable { mutableStateOf(startKey) }
    var assets by remember { mutableStateOf(listOf(SolanaAssets.native)) }
    var coinsRead by remember { mutableStateOf(false) }
    var assetSheet by remember { mutableStateOf(false) }
    var changeRecipient by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<BigInteger?>(null) }
    // The rent of the one-time setup while the account has no nonce account, or null once it has one. Use Max keeps it back from the balance.
    var setupRent by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientStatus by remember { mutableStateOf(RECIPIENT_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanReading by remember { mutableStateOf<ReplyReading?>(null) }
    var scanNotice by remember { mutableStateOf<String?>(null) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentSignature by remember { mutableStateOf<String?>(null) }
    // The Priority Fee switch of the fee sheet. It is saved, so that a rotation keeps the owner's choice.
    var priorityFee by rememberSaveable { mutableStateOf(false) }
    var feeSheet by remember { mutableStateOf(false) }

    // The chosen coin is offered once the account's coins are read. Until then, and for a token that the send does not offer, the send goes no further.
    val coinOffered = assets.any { it.key == selectedKey }
    val selected = assets.firstOrNull { it.key == selectedKey } ?: SolanaAssets.native
    val token = selected.token
    val balanceText: String? = if (token == null) {
        balance?.let { "${SolanaSendInput.format(it)} SOL" }
    } else {
        "${SolanaAssets.text(token.amount, selected.decimals)} ${selected.symbol}"
    }
    val maxText: String? = if (token == null) {
        balance?.let { SolanaSend.maxLamports(it, priorityFee, setupRent) }?.let { SolanaSendInput.format(BigInteger.valueOf(it)) }
    } else {
        SolanaAssets.text(token.amount, selected.decimals)
    }
    val networkLabel = solanaNetworkName(network)
    val feeText = "${SolanaSendInput.format(BigInteger.valueOf(SOLANA_FEE_LAMPORTS))} SOL"

    /** The rent of the one-time setup while [from] has no nonce account, or null once it has one or when the chain cannot be read. */
    suspend fun setupRentOf(from: SolanaSendAccount): Long? = runCatching {
        val state = scheduler.run(SolanaAccountRead(network, Base58.encode(SolanaNonce.accountOf(from.key))), Priority.FOREGROUND)
        if (SolanaSend.noncePath(state, from.key) == SolanaNoncePath.Setup) {
            scheduler.run(RentExemptMinimum(network, SolanaNonce.ACCOUNT_BYTES), Priority.FOREGROUND)
        } else {
            null
        }
    }.getOrNull()

    // The balance, the coins and the setup's rent are read once the account is known: SOL, then the SPL tokens that the account holds. A failed token read leaves SOL alone.
    LaunchedEffect(account) {
        val from = account ?: return@LaunchedEffect
        val read = withContext(Dispatchers.IO) {
            val sol = runCatching { scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND) }.getOrNull()
            val tokens = runCatching { scheduler.run(SplTokenBalances(network, from.address), Priority.FOREGROUND) }.getOrDefault(emptyList())
            Triple(sol, SolanaAssets.choices(tokens, ChainHoldings.solanaNames(app)), setupRentOf(from))
        }
        balance = read.first
        assets = read.second
        setupRent = read.third
        coinsRead = true
    }

    /**
     * The plan of the send of the selected coin. A send names the account's nonce, and until the account has a nonce account the one-time setup comes
     * first. A token send opens the recipient's token account for the mint when it has none, and that account pays its rent.
     */
    fun review() {
        val from = account ?: return
        val asset = selected
        val priority = priorityFee
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val to = SolanaSendInput.recipient(recipient)
                    val units = if (asset.token == null) {
                        BigInteger.valueOf(SolanaSendInput.lamports(amount))
                    } else {
                        SolanaSendInput.tokenUnits(amount, asset.decimals)
                    }
                    val available = scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND)
                    val nonceAccount = SolanaNonce.accountOf(from.key)
                    val state = scheduler.run(SolanaAccountRead(network, Base58.encode(nonceAccount)), Priority.FOREGROUND)
                    when (val path = SolanaSend.noncePath(state, from.key)) {
                        SolanaNoncePath.Setup -> {
                            val rent = scheduler.run(RentExemptMinimum(network, SolanaNonce.ACCOUNT_BYTES), Priority.FOREGROUND)
                            SolanaSend.checkSetupFunds(available, rent)
                            SolanaSendStep.SetupReview(Base58.encode(nonceAccount), rent)
                        }
                        is SolanaNoncePath.Durable -> {
                            val mint = asset.token?.let { Base58.decode(it.mint) }
                            val exists = mint == null || SolanaSplSend.recipientAccountExists(
                                scheduler.run(SolanaAccountRead(network, Base58.encode(SolanaTokenAccounts.associatedAddress(to, mint))), Priority.FOREGROUND),
                            )
                            val rent = if (exists) 0L else scheduler.run(RentExemptMinimum(network, SolanaAssets.TOKEN_ACCOUNT_BYTES), Priority.FOREGROUND)
                            SolanaSendStep.Review(
                                SolanaAssets.plan(
                                    network, asset, from, to, units, available, path.nonceValue, SolanaSend.newRequestId(), exists, rent, priority,
                                ),
                                rent,
                            )
                        }
                    }
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { next -> step = next },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
    }

    fun confirm(plan: SolanaSendPlan, setup: Boolean = false) {
        runCatching { SolanaSend.requestParts(plan) }
            .onSuccess { parts ->
                notice = null
                step = SolanaSendStep.Request(plan, parts, setup)
            }
            .onFailure { notice = REQUEST_FAILED }
    }

    /** The setup at the latest blockhash, fetched now, so that the request Lensly signs names a blockhash that is as recent as the network allows. */
    fun prepareSetup(current: SolanaSendStep.SetupReview) {
        val from = account ?: return
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { SolanaSend.setupAt(scheduler, network, from, current.rentLamports) }
            }
            busy = false
            outcome.fold(
                onSuccess = { plan -> confirm(plan, setup = true) },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
    }

    /** A scanned recipient fills the form. A code that is refused keeps the camera on, with the words why. */
    fun onRecipientText(text: String) {
        runCatching { SolanaSendInput.recipientAddress(text) }
            .onSuccess { address ->
                recipient = address
                notice = null
                step = SolanaSendStep.Recipient
            }
            .onFailure { error -> recipientStatus = ownerWords(error, RECIPIENT_HINT) }
    }

    fun startScan(current: SolanaSendStep.Request) {
        scan = SignatureScan()
        scanReading = null
        scanNotice = null
        notice = null
        step = SolanaSendStep.Scanning(current.plan, current.parts, current.setup)
    }

    /** Checks Lensly's signature and holds the signed transaction. Nothing is sent until the owner sends it on the next page. */
    fun applySignature(current: SolanaSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanNotice = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { SolanaSend.signed(current.plan, ur) } }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanReading = null
                    scanNotice = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { send ->
                    val machine = SubmitMachine(
                        signed = send,
                        broadcast = { signedSend ->
                            withContext(Dispatchers.IO) {
                                // A setup whose blockhash has run out is not sent: the owner is told so, and the setup is started again.
                                current.plan.window?.let { SolanaSend.requireUnexpired(scheduler, network, it) }
                                SolanaSend.broadcast(scheduler, network, signedSend)
                            }
                        },
                        describe = { error -> solanaFailure(error, setup = current.setup) },
                    )
                    notice = null
                    step = SolanaSendStep.Submit(current.plan, current.parts, machine, current.setup)
                },
            )
        }
    }

    fun onSignatureText(current: SolanaSendStep.Scanning, text: String) {
        if (scanBusy) return
        when (val result = scan.onText(text)) {
            is SignatureStep.Reading -> {
                scanReading = ReplyReading(result.received, result.expected)
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

    /** Leaves a submit that did not send. A refusal for its blockhash or nonce goes to a step that makes a fresh one, with the words why. */
    fun leaveSubmit(current: SolanaSendStep.Submit) {
        val next = unsentExit(current)
        notice = if (next is SolanaSendStep.Request) null else (current.machine.state.value as? SubmitState.Failed)?.message
        step = next
    }

    /**
     * Done on a setup's submit page. The form comes back with the outcome, and the balance is read again, since the rent has left the account.
     */
    fun finishSetup() {
        val from = account ?: return
        notice = when (sentSignature?.let { app.arrivalsOf(session)?.landingNow(network, it) }) {
            Landing.LANDED -> SETUP_DONE
            Landing.REFUSED -> SETUP_FAILED
            else -> SETUP_PENDING
        }
        step = SolanaSendStep.Recipient
        scope.launch {
            balance = withContext(Dispatchers.IO) {
                runCatching { scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND) }.getOrNull()
            }
            setupRent = withContext(Dispatchers.IO) { setupRentOf(from) }
        }
    }

    BackHandler {
        when (val current = step) {
            SolanaSendStep.Recipient -> navController.popBackStack()
            SolanaSendStep.ScanRecipient -> step = SolanaSendStep.Recipient
            SolanaSendStep.Amount -> step = SolanaSendStep.Recipient
            is SolanaSendStep.Review -> {
                notice = null
                step = SolanaSendStep.Amount
            }
            is SolanaSendStep.SetupReview -> {
                notice = null
                step = SolanaSendStep.Amount
            }
            is SolanaSendStep.Request -> {
                notice = null
                step = if (current.setup) SolanaSendStep.Amount else SolanaSendStep.Review(current.plan)
            }
            is SolanaSendStep.Scanning -> step = SolanaSendStep.Request(current.plan, current.parts, current.setup)
            is SolanaSendStep.Declined -> navController.popBackStack()
            is SolanaSendStep.Submit -> when (current.machine.state.value) {
                SubmitState.Submitting -> Unit
                is SubmitState.Sent -> if (current.setup) finishSetup() else navController.popBackStack()
                else -> leaveSubmit(current)
            }
        }
    }

    val from = account ?: return
    val fromName = "Account ${from.index + 1}"
    val backLabel = navController.backLabel()
    when (val current = step) {
        SolanaSendStep.Recipient -> {
            // The error waits until the owner pauses, so it never shows while the first characters are typed.
            var settled by remember { mutableStateOf("") }
            LaunchedEffect(recipient) {
                delay(SETTLE_MS)
                settled = recipient
            }
            val invalid = settled.isNotBlank() && solanaRecipientError(settled) != null
            SendRecipientPage(
                value = recipient,
                onValueChange = {
                    recipient = it.trim()
                    notice = null
                },
                error = if (invalid) solanaRecipientError(settled) else null,
                hint = if (coinOffered) null else if (coinsRead) COIN_NOT_OFFERED else COIN_READING,
                canContinue = coinOffered && recipient.isNotBlank() && solanaRecipientError(recipient) == null,
                accounts = paired?.let { solanaOwnChoices(it, from.address) }.orEmpty(),
                addressBook = addressBook
                    .filter { solanaRecipientError(it.address) == null }
                    .map { entry -> SendChoice(title = entry.plainName(), detail = entry.plainLabel(), address = entry.address) },
                onPick = { choice -> recipient = choice.address },
                onScan = {
                    recipientStatus = RECIPIENT_HINT
                    step = SolanaSendStep.ScanRecipient
                },
                onContinue = { step = SolanaSendStep.Amount },
                backLabel = backLabel,
                offline = offline,
                onBack = { navController.popBackStack() },
                onLock = onLock,
            )
        }
        SolanaSendStep.ScanRecipient -> RecipientScanScreen(
            status = recipientStatus,
            onText = ::onRecipientText,
            onCancel = { step = SolanaSendStep.Recipient },
        )
        SolanaSendStep.Amount -> {
            val notes = remember(recipient, addressBook) { addressNotesOf(recipient, SOLANA_FAMILY, emptyMap(), addressBook) }
            val toName = nameOrAddress(notes, recipient)
            val amountError = SolanaAssets.amountError(amount, selected, if (token == null) balance else token.amount)
            val leaves = if (token == null && balance != null && amountError == null && amount.isNotBlank()) {
                runCatching { SolanaSendInput.lamports(amount) }.getOrNull()?.let { lamports ->
                    val left = balance!! - BigInteger.valueOf(lamports) - BigInteger.valueOf(SOLANA_FEE_LAMPORTS + SolanaSend.sendPriorityFee(priorityFee))
                    if (left.signum() >= 0) "Leaves ${SolanaSendInput.format(left)} SOL after the fee" else null
                }
            } else {
                null
            }
            SendAmountPage(
                symbol = selected.symbol,
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = recipient,
                fromTitle = fromName,
                fromDetail = balanceText?.let { "$it available" } ?: "Reading the network",
                asset = selected.symbol.takeIf { assets.size > 1 },
                onAsset = if (assets.size > 1) { { assetSheet = true } } else null,
                amount = amount,
                onAmount = { amount = it },
                amountError = amountError ?: notice,
                leaves = leaves,
                onUseMax = if (maxText != null) { { maxText.let { amount = it } } } else null,
                networkName = networkLabel,
                feeText = if (priorityFee) "$feeText and a priority fee" else feeText,
                onFee = { feeSheet = true },
                note = if (assets.size > 1) null else "Chosen for you: ${networkLabel}, the network this account sends on.",
                canReview = !busy && recipient.isNotBlank() && solanaRecipientError(recipient) == null && amount.isNotBlank() && amountError == null,
                onReview = ::review,
                onChangeRecipient = { changeRecipient = true },
                backLabel = backLabel,
                offline = offline,
                onBack = { step = SolanaSendStep.Recipient },
                onLock = onLock,
            )
            SolanaFeeSheet(
                visible = feeSheet,
                networkFee = "${SolanaSendInput.format(BigInteger.valueOf(SOLANA_FEE_LAMPORTS))} SOL",
                timing = "About 1 second on $networkLabel",
                priority = priorityFee,
                onApply = { priorityFee = it },
                onClose = { feeSheet = false },
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
                        step = SolanaSendStep.Recipient
                    },
                    onKeep = { changeRecipient = false },
                )
            }
        }
        is SolanaSendStep.Review -> {
            val notes = remember(current.plan.recipientAddress, addressBook) {
                addressNotesOf(current.plan.recipientAddress, SOLANA_FAMILY, emptyMap(), addressBook)
            }
            val toName = nameOrAddress(notes, current.plan.recipientAddress)
            SendReviewPage(
                heading = "Send ${solanaAmountText(current.plan, selected.symbol)} from ${isolated(fromName)} to ${nameWithEnds(notes, current.plan.recipientAddress)}?",
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = current.plan.recipientAddress,
                validLine = "Address is valid on $networkLabel",
                firstTime = if (isFirstTime(notes)) "First time sending here. Compare every group with where you got it." else null,
                rows = solanaRows(current.plan, selected.symbol),
                warnings = lookalikeWarnings(notes) + if (current.newTokenAccountRent > 0) {
                    listOf("Creates their token account (≈ ${solAmountText(current.newTokenAccountRent)} SOL)")
                } else {
                    emptyList()
                },
                notice = notice,
                onShow = { confirm(current.plan) },
                onEdit = {
                    notice = null
                    step = SolanaSendStep.Amount
                },
                backLabel = backLabel,
                offline = offline,
                onBack = {
                    notice = null
                    step = SolanaSendStep.Amount
                },
                onLock = onLock,
            )
        }
        is SolanaSendStep.SetupReview -> SendReviewPage(
            heading = "Offline Signing",
            toTitle = "Nonce account",
            toDetail = "Created once for this account",
            toAddress = current.nonceAccount,
            validLine = "Create the nonce account for this account. You do this once.",
            firstTime = SETUP_SIGN_TEXT,
            rows = listOf(
                SummaryRow("Rent", "${SolanaSendInput.format(BigInteger.valueOf(current.rentLamports))} SOL"),
                SummaryRow("Network Fee", feeText),
                SummaryRow("Network", networkLabel),
                SummaryRow("From", groupAddress(from.address)),
            ),
            warnings = emptyList(),
            notice = notice,
            onShow = { prepareSetup(current) },
            onEdit = {
                notice = null
                step = SolanaSendStep.Amount
            },
            backLabel = backLabel,
            offline = offline,
            onBack = {
                notice = null
                step = SolanaSendStep.Amount
            },
            onLock = onLock,
        )
        is SolanaSendStep.Request -> SendShowPage(
            parts = current.parts,
            onNext = { startScan(current) },
            onCancel = { step = if (current.setup) SolanaSendStep.Amount else SolanaSendStep.Declined(current.plan) },
            backLabel = backLabel,
            offline = offline,
            onBack = {
                notice = null
                step = if (current.setup) SolanaSendStep.Amount else SolanaSendStep.Review(current.plan)
            },
            onLock = onLock,
        )
        is SolanaSendStep.Scanning -> SignatureScanScreen(
            reading = scanReading,
            notice = scanNotice,
            onText = { text -> onSignatureText(current, text) },
            onBack = { step = SolanaSendStep.Request(current.plan, current.parts, current.setup) },
            onCancel = {
                step = if (current.setup) SolanaSendStep.Request(current.plan, current.parts, current.setup) else SolanaSendStep.Declined(current.plan)
            },
            backLabel = backLabel,
            onLock = onLock,
        )
        is SolanaSendStep.Declined -> SendDeclinedPage(
            amount = solanaAmountText(current.plan, selected.symbol),
            fromName = fromName,
            backLabel = backLabel,
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
        is SolanaSendStep.Submit -> SignedSendSteps(
            machine = current.machine,
            headline = if (current.setup) "Offline Signing Is on Its Way" else "Your ${selected.symbol} Is on Its Way",
            amount = solanaAmountText(current.plan, selected.symbol),
            recipient = if (current.setup) "your nonce account" else groupAddress(current.plan.recipientAddress),
            network = networkLabel,
            timing = "About 1 second on $networkLabel",
            fromName = fromName,
            arrival = app.arrivalsOf(session)?.let { arrivals ->
                ArrivalWatch(
                    arrivals = arrivals,
                    network = current.plan.network,
                    arrivedHeadline = if (current.setup) null else "Your ${selected.symbol} Has Arrived",
                    read = SolanaSendLanding(scheduler, current.plan)::landing,
                )
            },
            explorerUrl = { signature -> SolanaSend.explorerTxUrl(current.plan.network, signature) },
            onExplorer = { signature -> openExplorer(SolanaSend.explorerTxUrl(current.plan.network, signature)) },
            onSent = { signature -> sentSignature = signature },
            onStop = { navController.popBackStack() },
            onDone = { if (current.setup) finishSetup() else navController.popBackStack() },
            onReview = { leaveSubmit(current) },
            lastChanceSeconds = transactions.lastChanceSeconds,
            backLabel = backLabel,
            offline = offline,
            onLock = onLock,
        )
    }
}

/**
 * The step that leaves a submit which did not send. A failure that is not retryable is a blockhash or nonce that the network refuses (see
 * [solanaFailure]). The request that names it is never shown again: a setup goes back to its review for a fresh blockhash, and a send goes back
 * to its form, whose review reads the nonce that the network holds now. Every other failure returns to the request, as before.
 */
internal fun unsentExit(current: SolanaSendStep.Submit): SolanaSendStep {
    val refused = (current.machine.state.value as? SubmitState.Failed)?.retryable == false
    return when {
        !refused -> SolanaSendStep.Request(current.plan, current.parts, current.setup)
        current.setup -> SolanaSendStep.SetupReview(current.plan.recipientAddress, current.plan.lamports)
        else -> SolanaSendStep.Amount
    }
}

/** The words a failure shows: the owner's words for a refusal, a server's refusal, or a failed network read, else [fallback]. */
private fun ownerWords(error: Throwable, fallback: String): String = when (error) {
    is IllegalArgumentException -> error.message ?: fallback
    is ProviderError.Fatal -> error.message ?: fallback
    is ProviderError -> NETWORK_FAILED
    else -> fallback
}

/**
 * The words of a failed Solana submission. The network refuses a transaction whose blockhash it does not know, and the words say which of the two
 * that was: a setup whose blockhash aged out is started again, and a send whose nonce moved is made again. Neither can be sent as it is, so the
 * screen offers no retry.
 */
internal fun solanaFailure(error: Throwable, setup: Boolean): SubmitFailure {
    if (error is BlockhashExpired) return SubmitFailure(SETUP_EXPIRED, retryable = false)
    val refused = error is ProviderError.Fatal && error.message == JsonRpc.BLOCKHASH_REFUSED_MESSAGE
    return if (refused) {
        SubmitFailure(if (setup) SETUP_EXPIRED else NONCE_MOVED, retryable = false)
    } else {
        SubmitFailure(ownerWords(error, BROADCAST_FAILED))
    }
}

/** The name of a Solana network on the send pages. */
private fun solanaNetworkName(network: NetworkId): String = if (network.testnet) "Solana Devnet" else "Solana Mainnet-Beta"

/** The reason [text] is not a Solana address or a `solana:` link to one, or null when it is. */
private fun solanaRecipientError(text: String): String? = runCatching { SolanaSendInput.recipientAddress(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

/** The amount a send moves, in the coin it moves: lamports as SOL, or the token's units in its symbol. */
private fun solanaAmountText(plan: SolanaSendPlan, symbol: String): String {
    val token = plan.token
    return if (token == null) {
        "${SolanaSendInput.format(BigInteger.valueOf(plan.lamports))} SOL"
    } else {
        "${SolanaAssets.text(token.amount, token.decimals)} $symbol"
    }
}

/**
 * The rows under the recipient on the Review page: the amount, the network fee in SOL, the priority fee when the switch set one (an upper bound, as
 * Lensly shows it), the network and the sender.
 */
private fun solanaRows(plan: SolanaSendPlan, symbol: String): List<SummaryRow> = buildList {
    add(SummaryRow("Amount", solanaAmountText(plan, symbol)))
    add(SummaryRow("Network Fee", "${SolanaSendInput.format(BigInteger.valueOf(plan.feeLamports))} SOL"))
    if (plan.priorityFeeLamports > 0) {
        add(SummaryRow("Priority Fee", "up to ${SolanaSendInput.format(BigInteger.valueOf(plan.priorityFeeLamports))} SOL"))
    }
    add(SummaryRow("Network", solanaNetworkName(plan.network)))
    add(SummaryRow("From", groupAddress(plan.senderAddress)))
}

/** A rent in lamports as SOL, rounded to a thousandth as the copy shows it. */
private fun solAmountText(lamports: Long): String =
    BigDecimal.valueOf(lamports).movePointLeft(9).setScale(3, RoundingMode.HALF_UP).toPlainString()

/** The balance an asset row of the Amount page shows, or that it is the native coin. */
private fun assetDetail(asset: SolanaSendAsset): String {
    val token = asset.token ?: return "Native Coin"
    return "Balance ${SolanaAssets.text(token.amount, asset.decimals)} ${asset.symbol}"
}

