package app.lumio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.navigation.NavHostController
import app.lensly.DebugBtcFixture
import app.airvault.core.model.AccountId
import app.lensly.app.InternalAccount
import app.lensly.app.VaultHolder
import app.lensly.app.export.partsOf
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.vault.VaultSession
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSend
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.PreparedSend
import app.lumio.evm.RpcFailure
import app.lumio.evm.SendAssets
import app.lumio.evm.SendInput
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.pair.PairedWallet
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallets
import app.lumio.pair.ownAccountsOf
import app.lumio.send.EvmPaymentCode
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
import app.lumio.tokens.TokenPortfolio
import app.lumio.wallets.WalletAccounts
import app.lumio.ui.send.RECIPIENT_SCAN_HINT
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SendFormScreen
import app.lumio.ui.send.SendReviewScreen
import app.lumio.ui.send.SubmitScreen
import app.lumio.ui.send.evmSummary
import app.lumio.ui.send.SignRequestScreen
import app.lumio.ui.send.SignatureScanScreen
import com.sparrowwallet.hummingbird.UR
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
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
    /** The Ethereum accounts of every paired wallet, which the review names. */
    val accounts: Map<String, InternalAccount>,
)

/** [Missing] when the wallet, its EVM account or the address is not in the open vault, otherwise the context. */
private sealed interface SendLoad {
    data object Missing : SendLoad

    class Ready(val context: SendContext) : SendLoad
}

/** The screens of a send, in the order the owner goes through them. */
private sealed interface SendStep {
    data object Form : SendStep

    /** The camera reads a recipient: an address, or a payment code that fills the form. */
    data object ScanRecipient : SendStep

    class Review(val send: PreparedSend) : SendStep

    class Request(val send: PreparedSend, val requestId: ByteArray, val parts: List<String>) : SendStep

    class Scanning(val send: PreparedSend, val requestId: ByteArray, val parts: List<String>) : SendStep

    /** The signed transaction, held in memory until the owner submits it. Leaving the screen discards it. */
    class Submit(val send: PreparedSend, val requestId: ByteArray, val parts: List<String>, val machine: SubmitMachine<ByteArray, String>) : SendStep
}

private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_READ = "Signature read. Check the transaction, then submit it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NO_BROWSER = "No browser can open the explorer."
private const val REQUEST_ID_BYTES = 16

/**
 * A send from one address of a paired wallet, on the network [chainId]: the form, the review, the request that Lensly signs, the scan of its
 * signature, and the transaction that is sent. The network is fixed, so the form offers no other. Lumio holds no key. It builds the transaction
 * from public reads, and it broadcasts only a signature that answers its own request from the account that sends. The form starts on the native coin.
 */
@Composable
internal fun SendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    account: AccountId,
    mode: NetworkMode,
    chainId: Long,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
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

    var step by remember { mutableStateOf<SendStep>(SendStep.Form) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    // The contract of the token the send moves, or null for the native coin of the chain.
    var contract by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentNotice by remember { mutableStateOf<String?>(null) }
    // The send the network prepared. The review's Advanced sheet edits a copy of it, and the warnings compare against it.
    var suggested by remember { mutableStateOf<PreparedSend?>(null) }

    // The network the send is bound to. Null when that network is off, and the form then says so.
    val selectedChain = ready.chains.firstOrNull { it.chainId == chainId }
    // The coins the chain offers: the native coin, the tokens held here, and the preferred token. The send moves the chosen one, native by default.
    val assets = selectedChain?.let { chain -> SendAssets.onChain(chain, ready.held) }.orEmpty()
    val asset = assets.firstOrNull { it.token?.contract == contract } ?: assets.firstOrNull()

    fun review() {
        val selected = asset ?: return
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val to = SendInput.recipient(recipient)
                    val value = SendInput.amountWei(amount, selected.decimals, selected.symbol)
                    EvmSend.prepare(app.evmReadsFor(checkNotNull(session)), selected.chain, ready.from, ready.index, to, value, selected.token)
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { send ->
                    suggested = send
                    step = SendStep.Review(send)
                },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
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
                step = SendStep.Form
            }
            .onFailure { error -> scanStatus = ownerWords(error, RECIPIENT_SCAN_HINT) }
    }

    fun confirm(send: PreparedSend) {
        val requestId = ByteArray(REQUEST_ID_BYTES).also { SecureRandom().nextBytes(it) }
        runCatching { partsOf(EthSignRequestEncoder.encode(EvmSend.request(send, ready.wallet.fingerprint, requestId))) }
            .onSuccess { parts ->
                notice = null
                step = SendStep.Request(send, requestId, parts)
            }
            .onFailure { notice = REQUEST_FAILED }
    }

    fun startScan(current: SendStep.Request) {
        scan = SignatureScan()
        scanProgress = null
        scanStatus = SCAN_HINT
        notice = null
        step = SendStep.Scanning(current.send, current.requestId, current.parts)
    }

    /** Checks Lensly's signature and holds the signed transaction. Nothing is sent until the owner submits it on the next screen. */
    fun applySignature(current: SendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { EvmSend.signed(current.send, current.requestId, ur) } }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { raw ->
                    val reads = app.evmReadsFor(checkNotNull(session))
                    val chain = current.send.chain
                    val machine = SubmitMachine(
                        signed = raw,
                        broadcast = { bytes -> withContext(Dispatchers.IO) { EvmSend.broadcast(reads, chain, bytes) } },
                        describe = { error -> SubmitFailure(ownerWords(error, BROADCAST_FAILED)) },
                    )
                    notice = null
                    step = SendStep.Submit(current.send, current.requestId, current.parts, machine)
                },
            )
        }
    }

    /** Sends the signed transaction. A retry sends the same signed bytes again. */
    fun submit(current: SendStep.Submit) {
        scope.launch { current.machine.submit() }
    }

    fun onSignatureText(current: SendStep.Scanning, text: String) {
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
        if (current is SendStep.Scanning) DebugBtcFixture.take()?.split(' ')?.forEach { onSignatureText(current, it) }
    }

    fun openExplorer(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            sentNotice = NO_BROWSER
        }
    }

    BackHandler {
        when (val current = step) {
            SendStep.Form -> navController.popBackStack()
            SendStep.ScanRecipient -> step = SendStep.Form
            is SendStep.Review -> {
                notice = null
                step = SendStep.Form
            }
            is SendStep.Request -> {
                notice = null
                step = SendStep.Review(current.send)
            }
            is SendStep.Scanning -> step = SendStep.Request(current.send, current.requestId, current.parts)
            is SendStep.Submit -> when (current.machine.state.value) {
                SubmitState.Submitting -> Unit
                is SubmitState.Sent -> navController.popBackStack()
                else -> {
                    notice = null
                    step = SendStep.Request(current.send, current.requestId, current.parts)
                }
            }
        }
    }

    when (val current = step) {
        SendStep.Form -> SendFormScreen(
            fromLabel = "Address ${ready.index + 1}",
            fromAddress = ready.from,
            chain = selectedChain,
            assets = assets,
            selectedAsset = asset,
            onSelectAsset = { contract = it.token?.contract },
            recipient = recipient,
            onRecipient = { recipient = it },
            onScan = {
                scanStatus = RECIPIENT_SCAN_HINT
                step = SendStep.ScanRecipient
            },
            amount = amount,
            onAmount = { amount = it },
            busy = busy,
            notice = notice,
            onReview = ::review,
            onBack = { navController.popBackStack() },
        )
        SendStep.ScanRecipient -> RecipientScanScreen(
            status = scanStatus,
            onText = ::onRecipientText,
            onCancel = { step = SendStep.Form },
        )
        is SendStep.Review -> SendReviewScreen(
            send = current.send,
            suggested = suggested ?: current.send,
            addressBook = ready.wallet.addressBook,
            accounts = ready.accounts,
            notice = notice,
            onSave = { edited ->
                notice = null
                step = SendStep.Review(edited)
            },
            onConfirm = { confirm(current.send) },
            onBack = {
                notice = null
                step = SendStep.Form
            },
        )
        is SendStep.Request -> SignRequestScreen(
            send = current.send,
            parts = current.parts,
            notice = notice,
            onScan = { startScan(current) },
            onBack = {
                notice = null
                step = SendStep.Review(current.send)
            },
        )
        is SendStep.Scanning -> SignatureScanScreen(
            progress = scanProgress,
            status = scanStatus,
            onText = { text -> onSignatureText(current, text) },
            onCancel = { step = SendStep.Request(current.send, current.requestId, current.parts) },
        )
        is SendStep.Submit -> SubmitScreen(
            machine = current.machine,
            rows = evmSummary(current.send),
            explorerUrl = { hash -> EvmSend.explorerLink(current.send.chain, hash) },
            status = null,
            explorerNotice = sentNotice,
            onSubmit = { submit(current) },
            onOpenExplorer = { hash -> EvmSend.explorerLink(current.send.chain, hash)?.let(::openExplorer) },
            onStartOver = {
                notice = null
                step = SendStep.Form
            },
            onBack = {
                notice = null
                step = SendStep.Request(current.send, current.requestId, current.parts)
            },
            onDone = { navController.popBackStack() },
        )
    }
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
