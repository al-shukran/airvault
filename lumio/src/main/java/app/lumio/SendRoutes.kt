package app.lumio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.airvault.core.model.AccountId
import app.lensly.DebugBtcFixture
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.EVM_FAMILY
import app.lensly.app.InternalAccount
import app.lensly.app.VaultHolder
import app.lensly.app.addressKeyOf
import app.lensly.app.addressNotesOf
import app.lensly.app.export.partsOf
import app.lensly.core.registry.Amounts
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.evm.EvmAdvanced
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmFormat
import app.lumio.evm.EvmSend
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.PreparedSend
import app.lumio.evm.RpcFailure
import app.lumio.evm.SendAsset
import app.lumio.evm.SendAssets
import app.lumio.evm.SendInput
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.pair.ownAccountsOf
import app.lumio.send.ArrivalWatch
import app.lumio.send.EvmPaymentCode
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
import app.lumio.tokens.TokenPortfolio
import app.lumio.ui.send.RECIPIENT_SCAN_HINT
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.recipientError
import app.lumio.ui.send.SendChangeRecipientDialog
import app.lumio.ui.send.SendChoice
import app.lumio.ui.send.SendDeclinedPage
import app.lumio.ui.send.SendFeeSheet
import app.lumio.ui.send.SendOption
import app.lumio.ui.send.SendOptionSheet
import app.lumio.ui.send.SendOutcomePage
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
 * What a send reads from the open vault: the wallet, the address that sends, the settings, the chains that are on, and the tokens that the sending
 * address holds in the balance cache.
 */
private class SendContext(
    val wallet: PairedWallet,
    /** The place of the sending account among the wallet's Ethereum accounts. */
    val index: Int,
    val from: String,
    val settings: EvmSettings,
    val chains: List<EvmChain>,
    val held: List<TokenHolding>,
    /** The Ethereum accounts of every paired wallet, which the send names. */
    val accounts: Map<String, InternalAccount>,
)

/** [Missing] when the wallet, its EVM account or the address is not in the open vault, otherwise the context. */
private sealed interface SendLoad {
    data object Missing : SendLoad

    class Ready(val context: SendContext) : SendLoad
}

/**
 * The pages of a send, in the order the owner goes through them: Recipient, Amount, Review, Show to Lensly, the scan of Lensly's reply, and then the
 * signed transaction, which is Last Chance, On Its Way or a failure. The branches are Lensly Didn't Sign, the signature for another send, and the
 * change of recipient.
 */
private sealed interface SendStep {
    data object Recipient : SendStep

    /** The camera reads a recipient: an address, or a payment code that fills the form. */
    data object ScanRecipient : SendStep

    data object Amount : SendStep

    class Review(val send: PreparedSend) : SendStep

    class Show(val send: PreparedSend, val requestId: ByteArray, val parts: List<String>) : SendStep

    class Scanning(val send: PreparedSend, val requestId: ByteArray, val parts: List<String>) : SendStep

    /** Lensly's reply answers another send, so it is refused, and it is never sent. */
    class Mismatch(val send: PreparedSend, val requestId: ByteArray, val parts: List<String>) : SendStep

    /** Lensly's signature is held in memory, and the transaction waits for Last Chance. Leaving the page discards it. */
    class Signed(val send: PreparedSend, val machine: SubmitMachine<ByteArray, String>) : SendStep

    /** The owner ended the send before Lensly's signature was scanned, so nothing reached Lumio. */
    class Declined(val send: PreparedSend) : SendStep
}

private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_READ = "Signature read. Check the transaction, then send it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NO_BROWSER = "No browser can open the explorer."
private const val REQUEST_ID_BYTES = 16
private const val PAUSE_BEFORE_PREPARE_MS = 350L
private const val SETTLE_MS = 700L
private const val BITCOIN_PREFIX_MAIN = "bc1"
private const val BITCOIN_PREFIX_TEST = "tb1"

/**
 * A send from one address of a paired wallet, on the network [chainId]: the recipient, the amount and the fee, the sentence that Lensly signs, the
 * request as a code, the scan of Lensly's reply, Last Chance, and the transaction that is sent. The network is fixed by the account, so the send offers
 * no other. Lumio holds no key. It builds the transaction from public reads, and it sends only a signature that answers its own request from the account
 * that sends.
 */
@Composable
internal fun SendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    account: AccountId,
    mode: NetworkMode,
    chainId: Long,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val offline = rememberSendOffline(app)
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val load by produceState<SendLoad?>(initialValue = null, session, walletId, account, mode) {
        value = if (session == null) null else withContext(Dispatchers.IO) { loadSend(app, session, walletId, account, mode) }
    }
    if (load is SendLoad.Missing) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val ready = (load as? SendLoad.Ready)?.context ?: return

    var step by remember { mutableStateOf<SendStep>(SendStep.Recipient) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    // The contract of the token the send moves, or null for the native coin of the chain.
    var contract by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientScanStatus by remember { mutableStateOf(RECIPIENT_SCAN_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var changeRecipient by remember { mutableStateOf(false) }
    var assetSheet by remember { mutableStateOf(false) }
    var feeSheet by remember { mutableStateOf(false) }
    // The send the network prepared for the amount as typed, and the send with the owner's fee edits. Review and Lensly both take the edited one.
    var suggested by remember { mutableStateOf<PreparedSend?>(null) }
    var edited by remember { mutableStateOf<PreparedSend?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf<String?>(null) }

    // The network the send is bound to. Null when that network is off, and the page then says so.
    val selectedChain = ready.chains.firstOrNull { it.chainId == chainId }
    // The coins the chain offers: the native coin, and the tokens held here. The send moves the chosen one, native by default.
    val assets = selectedChain?.let { chain -> SendAssets.onChain(chain, ready.held) }.orEmpty()
    val asset = assets.firstOrNull { it.token?.contract == contract } ?: assets.firstOrNull()
    val clipboard = LocalClipboardManager.current
    val fromName = fromNameOf(ready)

    // The amount is read again after the owner pauses typing. A send that cannot be prepared says why under the amount.
    LaunchedEffect(step is SendStep.Amount, recipient, amount, contract) {
        if (step !is SendStep.Amount) return@LaunchedEffect
        val selected = asset ?: return@LaunchedEffect
        suggested = null
        edited = null
        amountError = null
        if (amount.isBlank()) return@LaunchedEffect
        val problem = runCatching { SendInput.amountWei(amount, selected.decimals, selected.symbol) }.exceptionOrNull()
        if (problem != null) {
            amountError = problem.message ?: PREPARE_FAILED
            return@LaunchedEffect
        }
        delay(PAUSE_BEFORE_PREPARE_MS)
        preparing = true
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val to = SendInput.recipient(recipient)
                val value = SendInput.amountWei(amount, selected.decimals, selected.symbol)
                EvmSend.prepare(app.evmReadsFor(checkNotNull(session)), selected.chain, ready.from, ready.index, to, value, selected.token)
            }
        }
        preparing = false
        outcome.fold(
            onSuccess = { send ->
                suggested = send
                edited = send
            },
            onFailure = { error -> amountError = ownerWords(error, PREPARE_FAILED) },
        )
    }

    /**
     * A scanned recipient fills To, and the amount when the form has none. A payment code's amount is in the native coin, so it fills the amount
     * only for the native coin. A code that is refused keeps the camera on, with the words why.
     */
    fun onRecipientText(text: String) {
        val chain = selectedChain ?: return
        runCatching { EvmPaymentCode.read(text, chain) }
            .onSuccess { payment ->
                recipient = payment.address
                if (payment.amount != null && amount.isBlank() && asset?.token == null) amount = payment.amount
                notice = null
                step = SendStep.Recipient
            }
            .onFailure { error -> recipientScanStatus = ownerWords(error, RECIPIENT_SCAN_HINT) }
    }

    fun confirm(send: PreparedSend) {
        val requestId = ByteArray(REQUEST_ID_BYTES).also { SecureRandom().nextBytes(it) }
        runCatching { partsOf(EthSignRequestEncoder.encode(EvmSend.request(send, ready.wallet.fingerprint, requestId))) }
            .onSuccess { parts ->
                notice = null
                step = SendStep.Show(send, requestId, parts)
            }
            .onFailure { notice = REQUEST_FAILED }
    }

    fun startScan(send: PreparedSend, requestId: ByteArray, parts: List<String>) {
        scan = SignatureScan()
        scanProgress = null
        scanStatus = SCAN_HINT
        notice = null
        step = SendStep.Scanning(send, requestId, parts)
    }

    /** Checks Lensly's signature and holds the signed transaction. Nothing is sent until Last Chance is over or the owner sends it now. */
    fun applySignature(scanning: SendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { EvmSend.signed(scanning.send, scanning.requestId, ur) } }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanProgress = null
                    if (error is EvmSend.SignatureForAnotherSend) {
                        step = SendStep.Mismatch(scanning.send, scanning.requestId, scanning.parts)
                    } else {
                        scanStatus = ownerWords(error, SIGNATURE_FAILED)
                    }
                },
                onSuccess = { raw ->
                    val reads = app.evmReadsFor(checkNotNull(session))
                    val chain = scanning.send.chain
                    val machine = SubmitMachine(
                        signed = raw,
                        broadcast = { bytes -> withContext(Dispatchers.IO) { EvmSend.broadcast(reads, chain, bytes) } },
                        describe = { error -> SubmitFailure(ownerWords(error, BROADCAST_FAILED)) },
                    )
                    notice = null
                    step = SendStep.Signed(scanning.send, machine)
                },
            )
        }
    }

    fun onSignatureText(scanning: SendStep.Scanning, text: String) {
        if (scanBusy) return
        when (val result = scan.onText(text)) {
            is SignatureStep.Reading -> {
                scanProgress = result.fraction
                scanStatus = SCAN_READING
            }
            is SignatureStep.Complete -> applySignature(scanning, result.ur)
            SignatureStep.Ignored -> Unit
        }
    }

    // A debug build can read a fixture in place of the camera, so the signature can be shown without one. Release builds have no fixture.
    LaunchedEffect(step) {
        val now = step
        if (now is SendStep.Scanning) DebugBtcFixture.take()?.split(' ')?.forEach { onSignatureText(now, it) }
    }

    fun openExplorer(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, NO_BROWSER, Toast.LENGTH_SHORT).show()
        }
    }

    val backLabel = navController.backLabel()
    BackHandler {
        when (val now = step) {
            SendStep.Recipient -> navController.popBackStack()
            SendStep.ScanRecipient -> step = SendStep.Recipient
            SendStep.Amount -> step = SendStep.Recipient
            is SendStep.Review -> step = SendStep.Amount
            is SendStep.Show -> step = SendStep.Review(now.send)
            is SendStep.Scanning -> step = SendStep.Show(now.send, now.requestId, now.parts)
            is SendStep.Mismatch -> step = SendStep.Scanning(now.send, now.requestId, now.parts)
            is SendStep.Signed, is SendStep.Declined -> navController.popBackStack()
        }
    }

    when (val now = step) {
        SendStep.Recipient -> {
            val chain = selectedChain
            // The error waits until the owner pauses, so it never shows while the first characters are typed.
            var settled by remember { mutableStateOf("") }
            LaunchedEffect(recipient) {
                delay(SETTLE_MS)
                settled = recipient
            }
            val invalid = settled.isNotBlank() && recipientError(settled) != null
            SendRecipientPage(
                value = recipient,
                onValueChange = {
                    recipient = it.trim()
                    notice = null
                },
                error = if (invalid && chain != null) "This isn't a ${chain.name} address" else null,
                hint = bitcoinHint(recipient),
                canContinue = chain != null && recipient.isNotBlank() && recipientError(recipient) == null,
                accounts = ready.accounts.filterKeys { it != addressKeyOf(ready.from) }.map { (address, account) ->
                    SendChoice(title = accountName(account), detail = "Your account", address = address)
                },
                addressBook = ready.wallet.addressBook.map { entry ->
                    SendChoice(title = entry.name, detail = entry.label ?: "Address Book", address = entry.address)
                },
                onPick = { choice -> recipient = choice.address },
                onPaste = { clipboard.getText()?.text?.trim()?.let { recipient = it } },
                onScan = {
                    recipientScanStatus = RECIPIENT_SCAN_HINT
                    step = SendStep.ScanRecipient
                },
                onContinue = { step = SendStep.Amount },
                backLabel = backLabel,
                offline = offline,
                onBack = { navController.popBackStack() },
                onLock = onLock,
            )
        }
        SendStep.ScanRecipient -> RecipientScanScreen(
            status = recipientScanStatus,
            onText = ::onRecipientText,
            onCancel = { step = SendStep.Recipient },
        )
        SendStep.Amount -> {
            val chain = selectedChain
            val send = edited
            val notes = notesFor(recipient, ready.accounts, ready.wallet.addressBook)
            val toName = notes?.let(::notesName) ?: groupAddress(recipient)
            val leaves = send?.takeIf { asset?.token == null }?.let { s ->
                val left = s.balanceWei - s.valueWei - s.maxFeeWei
                if (left.signum() < 0) null else "Leaves ${EvmFormat.amount(left, s.chain.decimals, s.chain.symbol)} after the fee"
            }
            SendAmountPage(
                symbol = asset?.symbol ?: chain?.symbol.orEmpty(),
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = recipient,
                fromTitle = fromName,
                fromDetail = send?.let { "${EvmFormat.amount(it.balanceWei, it.chain.decimals, it.chain.symbol)} available" } ?: "Your account",
                asset = asset?.symbol?.takeIf { assets.size > 1 },
                onAsset = if (assets.size > 1) { { assetSheet = true } } else null,
                amount = amount,
                onAmount = { amount = it },
                amountError = amountError,
                leaves = leaves,
                onUseMax = send?.let { s ->
                    {
                        val maxSpend = if (s.token == null) s.balanceWei - s.maxFeeWei else s.tokenBalance ?: BigInteger.ZERO
                        if (maxSpend.signum() > 0) amount = Amounts.format(maxSpend, asset?.decimals ?: 0)
                    }
                },
                networkName = chain?.name.orEmpty(),
                feeText = send?.let { "Up to ${EvmFormat.amount(it.maxFeeWei, it.chain.decimals, it.chain.symbol)}" },
                onFee = { feeSheet = true },
                note = if (assets.size > 1) null else chain?.let { "Chosen for you: ${it.name}, the network this account sends on." },
                canReview = send != null && !preparing && amountError == null,
                onReview = { send?.let { step = SendStep.Review(it) } },
                onChangeRecipient = { changeRecipient = true },
                backLabel = backLabel,
                offline = offline,
                onBack = { step = SendStep.Recipient },
                onLock = onLock,
            )
            if (assets.size > 1) {
                SendOptionSheet(
                    visible = assetSheet,
                    heading = "Asset",
                    options = assets.map { SendOption(title = it.symbol, detail = assetDetail(it)) },
                    onPick = { index ->
                        contract = assets[index].token?.contract
                        assetSheet = false
                    },
                    onClose = { assetSheet = false },
                )
            }
            val base = suggested
            if (send != null && base != null) {
                SendFeeSheet(
                    visible = feeSheet,
                    suggested = base,
                    current = send,
                    timing = "Arrives in about 1 second on ${send.chain.name}",
                    onApply = { applied -> edited = applied },
                    onClose = { feeSheet = false },
                )
            }
            if (changeRecipient) {
                SendChangeRecipientDialog(
                    keepLabel = toName,
                    onChange = {
                        changeRecipient = false
                        recipient = ""
                        step = SendStep.Recipient
                    },
                    onKeep = { changeRecipient = false },
                )
            }
        }
        is SendStep.Review -> {
            val notes = notesFor(now.send.to, ready.accounts, ready.wallet.addressBook)
            val toName = notes?.let(::notesName) ?: groupAddress(now.send.to)
            SendReviewPage(
                heading = reviewHeading(now.send, fromName, toName),
                toTitle = toName,
                toDetail = notes?.let(::notesDetail).orEmpty(),
                toAddress = now.send.to,
                validLine = "Address is valid on ${now.send.chain.name}",
                firstTime = if (notes == null) "First time sending here. Compare every group with where you got it." else null,
                rows = reviewRows(now.send, fromName),
                warnings = EvmAdvanced.warnings(suggested ?: now.send, now.send),
                notice = notice,
                onShow = { confirm(now.send) },
                onEdit = {
                    notice = null
                    step = SendStep.Amount
                },
                backLabel = backLabel,
                offline = offline,
                onBack = { step = SendStep.Amount },
                onLock = onLock,
            )
        }
        is SendStep.Show -> SendShowPage(
            parts = now.parts,
            onNext = { startScan(now.send, now.requestId, now.parts) },
            onCancel = { step = SendStep.Declined(now.send) },
            backLabel = backLabel,
            offline = offline,
            onBack = { step = SendStep.Review(now.send) },
            onLock = onLock,
        )
        is SendStep.Scanning -> SignatureScanScreen(
            progress = scanProgress,
            status = scanStatus,
            onText = { text -> onSignatureText(now, text) },
            onCancel = { step = SendStep.Declined(now.send) },
        )
        is SendStep.Mismatch -> {
            val toName = notesFor(now.send.to, ready.accounts, ready.wallet.addressBook)?.let(::notesName) ?: groupAddress(now.send.to)
            SendOutcomePage(
                title = "",
                heading = "This Signature Is for a Different Send",
                mark = "!",
                body = "Lensly's reply doesn't match this send. Nothing was sent, and your ${amountText(now.send)} is still in $fromName.",
                notes = listOf(
                    "What Happened" to "Lensly's reply doesn't match this send. Lumio never sends a signature that doesn't match.",
                    "What You Can Do" to "On Lensly, check that it shows ${amountText(now.send)} to $toName, then scan its reply again.",
                ),
                detail = null,
                primary = "Scan Again",
                onPrimary = { startScan(now.send, now.requestId, now.parts) },
                secondary = "Cancel Send",
                onSecondary = { step = SendStep.Declined(now.send) },
                backLabel = backLabel,
                onBack = { step = SendStep.Scanning(now.send, now.requestId, now.parts) },
                onLock = onLock,
            )
        }
        is SendStep.Declined -> SendDeclinedPage(
            amount = amountText(now.send),
            fromName = fromName,
            backLabel = backLabel,
            onBack = { navController.popBackStack() },
            onLock = onLock,
        )
        is SendStep.Signed -> SignedSendSteps(
            machine = now.machine,
            headline = "Your ${now.send.chain.symbol} Is on Its Way",
            amount = amountText(now.send),
            recipient = notesFor(now.send.to, ready.accounts, ready.wallet.addressBook)?.let(::notesName) ?: groupAddress(now.send.to),
            network = now.send.chain.name,
            timing = "Usually about 1 second on ${now.send.chain.name}",
            fromName = fromName,
            arrival = ArrivalWatch(
                arrivals = app.sendArrivals,
                network = NetworkId(Networks.evm(now.send.chain.chainId), now.send.chain.testnet),
                arrivedHeadline = "Your ${now.send.chain.symbol} Has Arrived",
                read = { hash -> EvmSend.landing(app.evmReadsFor(checkNotNull(session)), now.send, hash) },
            ),
            explorerUrl = { hash -> EvmSend.explorerLink(now.send.chain, hash) },
            onExplorer = { hash -> EvmSend.explorerLink(now.send.chain, hash)?.let(::openExplorer) },
            onSent = {},
            onStop = { navController.popBackStack() },
            onDone = { navController.popBackStack() },
            onReview = { step = SendStep.Review(now.send) },
            backLabel = backLabel,
            offline = offline,
            onLock = onLock,
        )
    }
}

/** The notes of [address]: the device's own accounts first, then the Address Book. Null when neither knows the address. */
private fun notesFor(address: String, accounts: Map<String, InternalAccount>, addressBook: List<AddressBookEntry>): AddressNotes? =
    addressNotesOf(address, EVM_FAMILY, accounts, addressBook)

/** The name an account goes by on the screens: its own name, else the name the app gave it. */
private fun accountName(account: InternalAccount?): String = account?.let { it.displayName ?: it.autoName }.orEmpty()

/** The name of the account the send is from, or "Account" with its place when the account has no name. */
private fun fromNameOf(ready: SendContext): String = accountName(ready.accounts[addressKeyOf(ready.from)]).ifBlank { "Account ${ready.index + 1}" }

internal fun notesName(notes: AddressNotes): String =notes.entry?.name ?: notes.internal?.let { it.displayName ?: it.autoName }.orEmpty()

internal fun notesDetail(notes: AddressNotes): String {
    if (notes.internal != null) return "Your account"
    val entry = notes.entry ?: return ""
    return entry.label ?: "Address Book"
}

private fun amountText(send: PreparedSend): String {
    val token = send.token
    return if (token == null) {
        EvmFormat.amount(send.valueWei, send.chain.decimals, send.chain.symbol)
    } else {
        EvmFormat.amount(send.valueWei, token.decimals, token.symbol)
    }
}

private fun reviewHeading(send: PreparedSend, fromName: String, toName: String): String =
    "Send ${amountText(send)} from $fromName to $toName?"

/** The rows under the recipient on the Review page, in the order Lensly shows them: the amount, the most the fee can be, the network and the sender. */
private fun reviewRows(send: PreparedSend, fromName: String): List<SummaryRow> = buildList {
    add(SummaryRow("Amount", amountText(send)))
    add(SummaryRow("Max Fee", EvmFormat.amount(send.maxFeeWei, send.chain.decimals, send.chain.symbol)))
    if (send.token == null) add(SummaryRow("Total at Most", EvmFormat.amount(send.maxCostWei, send.chain.decimals, send.chain.symbol)))
    add(SummaryRow("Network", "${send.chain.name} · ID ${send.chain.chainId}"))
    add(SummaryRow("From", fromName))
}

private fun assetDetail(asset: SendAsset): String {
    val token = asset.token ?: return "Native Coin"
    val balance = asset.balance ?: return "No balance on this address"
    return "Balance " + EvmFormat.amount(balance, token.decimals, token.symbol)
}

private fun bitcoinHint(text: String): String? =
    if (text.startsWith(BITCOIN_PREFIX_MAIN) || text.startsWith(BITCOIN_PREFIX_TEST)) {
        "It looks like a Bitcoin address. To send BTC, start from a Bitcoin account."
    } else {
        null
    }

/**
 * Reads the send's wallet, address and chains from the open vault, with the tokens each chain lists and the tokens the address holds in the
 * balance cache. The chains are the enabled ones on the side [mode] chooses, as on the wallet's portfolio. It runs off the main thread.
 */
private fun loadSend(app: LumioApp, session: VaultSession, walletId: String, account: AccountId, mode: NetworkMode): SendLoad = runCatching {
    val wallets = PairedWallets.list(session)
    val wallet = wallets.firstOrNull { it.id == walletId } ?: return@runCatching SendLoad.Missing
    val index = WalletAccounts.indexOf(wallet, account) ?: return@runCatching SendLoad.Missing
    val from = wallet.evmAccounts.getOrNull(index)?.address ?: return@runCatching SendLoad.Missing
    val settings = EvmSettingsStore.read(session)
    if (index !in 0 until settings.addressCount) return@runCatching SendLoad.Missing
    val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
    val tokensOf = app.tokensFor(session)
    val held = TokenPortfolio.holdingsAt(wallet.id, from, chains, tokensOf, TokenBalanceCache.read(session))
    SendLoad.Ready(SendContext(wallet, index, from, settings, chains, held, ownAccountsOf(wallets)))
}.getOrDefault(SendLoad.Missing)

/** The words a failure shows: the owner's words for a refusal (an invalid input, a signature that is not this one, an endpoint's answer), else [fallback]. */
private fun ownerWords(error: Throwable, fallback: String): String = when (error) {
    is IllegalArgumentException -> error.message ?: fallback
    is RpcFailure -> error.message ?: fallback
    else -> fallback
}
