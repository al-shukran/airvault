package app.lumio.send.solana

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
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.qr.ScanScreen
import app.lumio.LumioApp
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.RecentBlockhash
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.sched.Priority
import app.lumio.evm.SignatureScan
import app.lumio.evm.SignatureStep
import app.lumio.network.NetworkMode
import app.lumio.onVault
import app.lumio.pair.PairedWallets
import app.lumio.ui.send.solana.SolanaSendFormScreen
import app.lumio.ui.send.solana.SolanaSendReviewScreen
import app.lumio.ui.send.solana.SolanaSendSentScreen
import app.lumio.ui.send.solana.SolanaSignRequestScreen
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens of a Solana send, in the order the owner goes through them. */
private sealed interface SolanaSendStep {
    data object Form : SolanaSendStep

    /** The camera reads a recipient: an address, or a `solana:` link that holds one. */
    data object ScanRecipient : SolanaSendStep

    class Review(val plan: SolanaSendPlan) : SolanaSendStep

    class Request(val plan: SolanaSendPlan, val parts: List<String>) : SolanaSendStep

    class Scanning(val plan: SolanaSendPlan, val parts: List<String>) : SolanaSendStep

    /** The transaction that was sent. Its outcome is read after the screen opens. */
    class Sent(val network: NetworkId, val signature: String) : SolanaSendStep
}

private const val RECIPIENT_HINT = "Point the camera at a Solana address or a solana: link."
private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_SENDING = "Signature read. Sending the transaction to the network."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NETWORK_FAILED = "The Solana network could not be read. Try again."
private const val NO_BROWSER = "No browser can open the explorer."

/**
 * A Solana send from one paired account, which is fixed: the form, the review, the request that Lensly signs, the scan of its answer, and the status
 * of the transaction on the network. [walletId] and [accountIndex] name the account N of the wallet, and [mode] picks the devnet or mainnet-beta side.
 * Lumio holds no key. It builds the transfer from public reads, and it broadcasts only a signature that Lensly made for that message, after the
 * signature has been checked against the account's public key.
 */
@Composable
internal fun SolanaSendRoute(
    navController: NavHostController,
    holder: VaultHolder,
    walletId: String,
    accountIndex: Int,
    mode: NetworkMode,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val context = LocalContext.current
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val scope = rememberCoroutineScope()
    val scheduler = remember(session) { app.nativeSchedulerFor(session) }
    val network = if (mode == NetworkMode.TESTNET) SOLANA_DEVNET else SOLANA_MAINNET
    // The account N of the wallet, from the public key the pairing holds. Its private key stays with Lensly.
    val account by produceState<SolanaSendAccount?>(initialValue = null, session, walletId, accountIndex) {
        value = onVault(null) {
            PairedWallets.list(session).firstOrNull { it.id == walletId }?.let { wallet ->
                wallet.solana.getOrNull(accountIndex)?.let { key -> SolanaSendAccount(accountIndex, key, wallet.fingerprint.toInt()) }
            }
        }
    }

    var step by remember { mutableStateOf<SolanaSendStep>(SolanaSendStep.Form) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var balance by remember { mutableStateOf<BigInteger?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var recipientStatus by remember { mutableStateOf(RECIPIENT_HINT) }
    var scan by remember { mutableStateOf(SignatureScan()) }
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanStatus by remember { mutableStateOf(SCAN_HINT) }
    var scanBusy by remember { mutableStateOf(false) }
    var sentOutcome by remember { mutableStateOf<SolanaOutcome?>(null) }
    var sentNotice by remember { mutableStateOf<String?>(null) }

    // The balance is read once the account is known, for the form's balance line and its Max action.
    LaunchedEffect(account) {
        val from = account ?: return@LaunchedEffect
        balance = runCatching {
            withContext(Dispatchers.IO) { scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND) }
        }.getOrNull()
    }

    // After the send, the status is read until the network confirms it, fails it, or the polls run out.
    LaunchedEffect(step) {
        val current = step
        if (current is SolanaSendStep.Sent) {
            sentOutcome = withContext(Dispatchers.IO) {
                runCatching { SolanaSend.confirm(scheduler, current.network, current.signature) }.getOrDefault(SolanaOutcome.PENDING)
            }
        }
    }

    fun review() {
        val from = account ?: return
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val to = SolanaSendInput.recipient(recipient)
                    val lamports = SolanaSendInput.lamports(amount)
                    val blockhash = Base58.decode(scheduler.run(RecentBlockhash(network), Priority.FOREGROUND))
                    val available = scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND)
                    SolanaSend.plan(network, from, to, lamports, available, blockhash, SolanaSend.newRequestId())
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { plan -> step = SolanaSendStep.Review(plan) },
                onFailure = { error -> notice = ownerWords(error, PREPARE_FAILED) },
            )
        }
    }

    fun confirm(plan: SolanaSendPlan) {
        runCatching { SolanaSend.requestParts(plan) }
            .onSuccess { parts ->
                notice = null
                step = SolanaSendStep.Request(plan, parts)
            }
            .onFailure { notice = REQUEST_FAILED }
    }

    /** A scanned recipient fills the form. A code that is refused keeps the camera on, with the words why. */
    fun onRecipientText(text: String) {
        runCatching { SolanaSendInput.recipientAddress(text) }
            .onSuccess { address ->
                recipient = address
                notice = null
                step = SolanaSendStep.Form
            }
            .onFailure { error -> recipientStatus = ownerWords(error, RECIPIENT_HINT) }
    }

    fun startScan(current: SolanaSendStep.Request) {
        scan = SignatureScan()
        scanProgress = null
        scanStatus = SCAN_HINT
        notice = null
        step = SolanaSendStep.Scanning(current.plan, current.parts)
    }

    /** Checks Lensly's signature, then sends the transaction and shows its signature. A refused signature keeps the camera on. */
    fun applySignature(current: SolanaSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_SENDING
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { SolanaSend.signed(current.plan, ur) } }
            signed.fold(
                onFailure = { error ->
                    scanBusy = false
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { send ->
                    val sent = withContext(Dispatchers.IO) {
                        runCatching { SolanaSend.broadcast(scheduler, current.plan.network, send) }
                    }
                    scanBusy = false
                    sent.fold(
                        onFailure = { error ->
                            notice = ownerWords(error, BROADCAST_FAILED)
                            // An expired blockhash cannot be signed again, so the send is built again from the form.
                            val expired = error is ProviderError.Fatal && error.message == JsonRpc.BLOCKHASH_EXPIRED_MESSAGE
                            step = if (expired) SolanaSendStep.Form else SolanaSendStep.Request(current.plan, current.parts)
                        },
                        onSuccess = { signature ->
                            sentOutcome = null
                            sentNotice = null
                            step = SolanaSendStep.Sent(current.plan.network, signature)
                        },
                    )
                },
            )
        }
    }

    fun onSignatureText(current: SolanaSendStep.Scanning, text: String) {
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
            SolanaSendStep.Form, is SolanaSendStep.Sent -> navController.popBackStack()
            SolanaSendStep.ScanRecipient -> step = SolanaSendStep.Form
            is SolanaSendStep.Review -> {
                notice = null
                step = SolanaSendStep.Form
            }
            is SolanaSendStep.Request -> {
                notice = null
                step = SolanaSendStep.Review(current.plan)
            }
            is SolanaSendStep.Scanning -> step = SolanaSendStep.Request(current.plan, current.parts)
        }
    }

    val from = account ?: return
    when (val current = step) {
        SolanaSendStep.Form -> SolanaSendFormScreen(
            network = network,
            sender = from.address,
            balance = balance,
            recipient = recipient,
            onRecipient = { recipient = it },
            onScan = {
                recipientStatus = RECIPIENT_HINT
                step = SolanaSendStep.ScanRecipient
            },
            amount = amount,
            onAmount = { amount = it },
            onMax = {
                balance?.let { available -> SolanaSend.maxLamports(available)?.let { amount = SolanaSendInput.format(BigInteger.valueOf(it)) } }
            },
            busy = busy,
            notice = notice,
            onReview = ::review,
            onBack = { navController.popBackStack() },
        )
        SolanaSendStep.ScanRecipient -> ScanScreen(
            title = "Scan address",
            progress = null,
            statusText = recipientStatus,
            onText = ::onRecipientText,
            onCancel = { step = SolanaSendStep.Form },
        )
        is SolanaSendStep.Review -> SolanaSendReviewScreen(
            plan = current.plan,
            notice = notice,
            onConfirm = { confirm(current.plan) },
            onBack = {
                notice = null
                step = SolanaSendStep.Form
            },
        )
        is SolanaSendStep.Request -> SolanaSignRequestScreen(
            parts = current.parts,
            notice = notice,
            onScan = { startScan(current) },
            onBack = {
                notice = null
                step = SolanaSendStep.Review(current.plan)
            },
        )
        is SolanaSendStep.Scanning -> ScanScreen(
            title = "Scan signature",
            progress = scanProgress,
            statusText = scanStatus,
            onText = { text -> onSignatureText(current, text) },
            onCancel = { step = SolanaSendStep.Request(current.plan, current.parts) },
        )
        is SolanaSendStep.Sent -> SolanaSendSentScreen(
            network = current.network,
            signature = current.signature,
            outcome = sentOutcome,
            notice = sentNotice,
            onOpenExplorer = { openExplorer(SolanaSend.explorerTxUrl(current.network, current.signature)) },
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
