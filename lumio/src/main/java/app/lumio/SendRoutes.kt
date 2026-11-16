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
import app.lensly.app.VaultHolder
import app.lensly.app.export.partsOf
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
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
import app.lumio.send.EvmPaymentCode
import app.lumio.tokens.Token
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
import app.lumio.tokens.TokenPortfolio
import app.lumio.ui.send.RECIPIENT_SCAN_HINT
import app.lumio.ui.send.RecipientScanScreen
import app.lumio.ui.send.SendFormScreen
import app.lumio.ui.send.SendReviewScreen
import app.lumio.ui.send.SendSentScreen
import app.lumio.ui.send.SignRequestScreen
import app.lumio.ui.send.SignatureScanScreen
import com.sparrowwallet.hummingbird.UR
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a send reads from the open vault: the wallet, the address that sends, the settings, the chains that are on, the tokens each chain lists,
 * and the tokens that the sending address holds in the balance cache.
 */
private class SendContext(
    val wallet: PairedWallet,
    val from: String,
    val settings: EvmSettings,
    val chains: List<EvmChain>,
    val tokensOf: (EvmChain) -> List<Token>,
    val held: List<TokenHolding>,
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

    class Sent(val chain: EvmChain, val hash: String) : SendStep
}

private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_SENDING = "Signature read. Sending the transaction to the network."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NO_BROWSER = "No browser can open the explorer."
private const val REQUEST_ID_BYTES = 16

/**
 * A send from one address of a paired wallet: the form, the review, the request that Lensly signs, the scan of its signature, and the
 * transaction that is sent. Lumio holds no key. It builds the transaction from public reads, and it broadcasts only a signature that
 * answers its own request from the account that sends. [preferredChainId] and [preferredToken] start the form on that chain and that
 * ERC-20 token, as an asset's page does; without a token the form starts on the native coin.
 */
@Composable
internal fun SendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    index: Int,
    mode: NetworkMode,
    preferredChainId: Long? = null,
    preferredToken: String? = null,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    val scope = rememberCoroutineScope()
    val load by produceState<SendLoad?>(initialValue = null, session, walletId, index, mode) {
        value = if (session == null) null else withContext(Dispatchers.IO) { loadSend(app, session, walletId, index, mode) }
    }
    if (load is SendLoad.Missing) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val ready = (load as? SendLoad.Ready)?.context ?: return

    var step by remember { mutableStateOf<SendStep>(SendStep.Form) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var chainId by remember { mutableStateOf(preferredChainId) }
    // The contract of the token the send moves, or null for the native coin of the chain.
    var contract by remember { mutableStateOf(preferredToken) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentNotice by remember { mutableStateOf<String?>(null) }
    // The send the network prepared. The review's Advanced sheet edits a copy of it, and the warnings compare against it.
    var suggested by remember { mutableStateOf<PreparedSend?>(null) }

    val selectedChain = ready.chains.firstOrNull { it.chainId == chainId } ?: ready.chains.firstOrNull()
    // The coins the chain offers: the native coin, the tokens held here, and the preferred token. The send moves the chosen one, native by default.
    val assets = selectedChain?.let { chain ->
        SendAssets.onChain(chain, ready.tokensOf(chain), ready.held, preferredToken.takeIf { chain.chainId == preferredChainId })
    }.orEmpty()
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
                    EvmSend.prepare(app.evmReadsFor(checkNotNull(session)), selected.chain, ready.from, index, to, value, selected.token)
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

    fun applySignature(current: SendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_SENDING
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { EvmSend.signed(current.send, current.requestId, ur) } }
            signed.fold(
                onFailure = { error ->
                    scanBusy = false
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { raw ->
                    val sent = withContext(Dispatchers.IO) {
                        runCatching { EvmSend.broadcast(app.evmReadsFor(checkNotNull(session)), current.send.chain, raw) }
                    }
                    scanBusy = false
                    sent.fold(
                        onFailure = { error ->
                            notice = ownerWords(error, BROADCAST_FAILED)
                            step = SendStep.Request(current.send, current.requestId, current.parts)
                        },
                        onSuccess = { hash -> step = SendStep.Sent(current.send.chain, hash) },
                    )
                },
            )
        }
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
            SendStep.Form, is SendStep.Sent -> navController.popBackStack()
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
        }
    }

    when (val current = step) {
        SendStep.Form -> SendFormScreen(
            fromLabel = "Address ${index + 1}",
            fromAddress = ready.from,
            chains = ready.chains,
            selectedChainId = selectedChain?.chainId,
            onSelectChain = {
                chainId = it
                contract = null
            },
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
        is SendStep.Sent -> SendSentScreen(
            chain = current.chain,
            hash = current.hash,
            explorerUrl = EvmSend.explorerLink(current.chain, current.hash),
            notice = sentNotice,
            onOpenExplorer = { EvmSend.explorerLink(current.chain, current.hash)?.let(::openExplorer) },
            onDone = { navController.popBackStack() },
        )
    }
}

/**
 * Reads the send's wallet, address and chains from the open vault, with the tokens each chain lists and the tokens the address holds in the
 * balance cache. The chains are the enabled ones on the side [mode] chooses, as on the wallet's portfolio. It runs off the main thread.
 */
private fun loadSend(app: LumioApp, session: VaultSession, walletId: String, index: Int, mode: NetworkMode): SendLoad = runCatching {
    val wallet = PairedWallets.list(session).firstOrNull { it.id == walletId } ?: return@runCatching SendLoad.Missing
    val account = wallet.evmAccounts.getOrNull(index)?.key ?: return@runCatching SendLoad.Missing
    val settings = EvmSettingsStore.read(session)
    if (index !in 0 until settings.addressCount) return@runCatching SendLoad.Missing
    val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
    val from = AccountAddresses.addressOfKey(account)
    val tokensOf = app.tokensFor(session)
    val held = TokenPortfolio.holdingsAt(wallet.id, from, chains, tokensOf, TokenBalanceCache.read(session))
    SendLoad.Ready(SendContext(wallet, from, settings, chains, tokensOf, held))
}.getOrDefault(SendLoad.Missing)

/** The words a failure shows: the owner's words for a refusal (an invalid input, a signature that is not this one, an endpoint's answer), else [fallback]. */
private fun ownerWords(error: Throwable, fallback: String): String = when (error) {
    is IllegalArgumentException -> error.message ?: fallback
    is RpcFailure -> error.message ?: fallback
    else -> fallback
}
