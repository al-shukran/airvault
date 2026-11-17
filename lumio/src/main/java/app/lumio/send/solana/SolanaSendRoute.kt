package app.lumio.send.solana

import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.send.SubmitState
import app.lumio.ui.send.SubmitScreen
import app.lumio.ui.send.solanaSummary
import app.lumio.ui.send.solana.solanaOutcomeText
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
import app.lensly.core.solana.SolanaNonce
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.qr.ScanScreen
import app.lumio.LumioApp
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.RecentBlockhash
import app.lumio.data.provider.RentExemptMinimum
import app.lumio.data.provider.SolanaAccountRead
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
import app.lumio.ui.send.solana.SolanaSetupReviewScreen
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

    /** The one-time setup of offline signing: the nonce account to create, and the rent paid into it. Shown before Lensly signs the setup. */
    class SetupReview(val nonceAccount: String, val rentLamports: Long) : SolanaSendStep

    /** A request for Lensly to sign. [setup] is true for the one-time setup, which is not a send. */
    class Request(val plan: SolanaSendPlan, val parts: List<String>, val setup: Boolean = false) : SolanaSendStep

    class Scanning(val plan: SolanaSendPlan, val parts: List<String>, val setup: Boolean = false) : SolanaSendStep

    /** The signed transaction, held in memory until the owner submits it. Leaving the screen discards it. [setup] is true for the one-time setup. */
    class Submit(
        val plan: SolanaSendPlan,
        val parts: List<String>,
        val machine: SubmitMachine<*, String>,
        val setup: Boolean = false,
    ) : SolanaSendStep
}

private const val RECIPIENT_HINT = "Point the camera at a Solana address or a solana: link."
private const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
private const val SCAN_READING = "Reading the signature. Keep the camera on it."
private const val SCAN_READ = "Signature read. Check the transaction, then submit it."
private const val PREPARE_FAILED = "The send could not be prepared. Try again."
private const val REQUEST_FAILED = "The sign request could not be made. Try again."
private const val SIGNATURE_FAILED = "That code could not be used. Scan the signature again."
private const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
private const val NETWORK_FAILED = "The Solana network could not be read. Try again."
private const val NO_BROWSER = "No browser can open the explorer."
private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
private const val NONCE_UNUSABLE = "The nonce account of this account cannot be used for a send."
private const val SETUP_DONE = "Offline signing is set up. Review the send again to sign it."
private const val SETUP_PENDING = "The setup is not confirmed yet. Review the send again in a minute."
private const val SETUP_FAILED = "The setup did not go through. Start it again from the send."

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
    var sentSignature by remember { mutableStateOf<String?>(null) }

    // The balance is read once the account is known, for the form's balance line and its Max action.
    LaunchedEffect(account) {
        val from = account ?: return@LaunchedEffect
        balance = runCatching {
            withContext(Dispatchers.IO) { scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND) }
        }.getOrNull()
    }

    // After the send, the status is read until the network confirms it, fails it, or the polls run out.
    LaunchedEffect(sentSignature) {
        val signature = sentSignature ?: return@LaunchedEffect
        sentOutcome = withContext(Dispatchers.IO) {
            runCatching { SolanaSend.confirm(scheduler, network, signature) }.getOrDefault(SolanaOutcome.PENDING)
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
                    val available = scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND)
                    // A send names the nonce that the account's nonce account holds. Until that account exists, the one-time setup comes first.
                    val nonceAccount = SolanaNonce.accountOf(from.key)
                    val state = scheduler.run(SolanaAccountRead(network, Base58.encode(nonceAccount)), Priority.FOREGROUND)
                    if (state == null) {
                        val rent = scheduler.run(RentExemptMinimum(network, SolanaNonce.ACCOUNT_BYTES), Priority.FOREGROUND)
                        SolanaSend.checkSetupFunds(available, rent)
                        SolanaSendStep.SetupReview(Base58.encode(nonceAccount), rent)
                    } else {
                        // The nonce account is the System Program's, and its data names this account as the authority.
                        val nonceValue = state.takeIf { it.owner == SYSTEM_PROGRAM }?.let { SolanaNonce.nonceValue(it.data, from.key) }
                            ?: throw IllegalArgumentException(NONCE_UNUSABLE)
                        SolanaSendStep.Review(SolanaSend.plan(network, from, to, lamports, available, nonceValue, SolanaSend.newRequestId()))
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

    /** The setup at a fresh blockhash, so that the request Lensly signs names a blockhash that is recent when the owner shows it. */
    fun prepareSetup(current: SolanaSendStep.SetupReview) {
        val from = account ?: return
        busy = true
        notice = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val blockhash = Base58.decode(scheduler.run(RecentBlockhash(network), Priority.FOREGROUND))
                    val available = scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND)
                    SolanaSend.setupPlan(network, from, current.rentLamports, available, blockhash, SolanaSend.newRequestId())
                }
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
                step = SolanaSendStep.Form
            }
            .onFailure { error -> recipientStatus = ownerWords(error, RECIPIENT_HINT) }
    }

    fun startScan(current: SolanaSendStep.Request) {
        scan = SignatureScan()
        scanProgress = null
        scanStatus = SCAN_HINT
        notice = null
        step = SolanaSendStep.Scanning(current.plan, current.parts, current.setup)
    }

    /** Checks Lensly's signature and holds the signed transaction. Nothing is sent until the owner submits it on the next screen. */
    fun applySignature(current: SolanaSendStep.Scanning, ur: UR) {
        scanBusy = true
        scanProgress = 1f
        scanStatus = SCAN_READ
        scope.launch {
            val signed = withContext(Dispatchers.IO) { runCatching { SolanaSend.signed(current.plan, ur) } }
            scanBusy = false
            signed.fold(
                onFailure = { error ->
                    scan = SignatureScan()
                    scanProgress = null
                    scanStatus = ownerWords(error, SIGNATURE_FAILED)
                },
                onSuccess = { send ->
                    val machine = SubmitMachine(
                        signed = send,
                        broadcast = { signedSend -> withContext(Dispatchers.IO) { SolanaSend.broadcast(scheduler, network, signedSend) } },
                        describe = { error -> solanaFailure(error) },
                    )
                    notice = null
                    step = SolanaSendStep.Submit(current.plan, current.parts, machine, current.setup)
                },
            )
        }
    }

    /** Sends the signed transaction, then reads its status. A retry sends the same signed bytes again. */
    fun submit(current: SolanaSendStep.Submit) {
        scope.launch {
            current.machine.submit()
            val state = current.machine.state.value
            if (state is SubmitState.Sent) {
                sentOutcome = null
                sentNotice = null
                sentSignature = state.id
            }
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

    /**
     * Done on a setup's submit screen. The form comes back with the outcome, and the balance is read again, since the rent has left the account.
     */
    fun finishSetup() {
        val from = account ?: return
        notice = when (sentOutcome) {
            SolanaOutcome.CONFIRMED -> SETUP_DONE
            SolanaOutcome.FAILED -> SETUP_FAILED
            else -> SETUP_PENDING
        }
        step = SolanaSendStep.Form
        scope.launch {
            balance = withContext(Dispatchers.IO) {
                runCatching { scheduler.run(NativeBalance(network, from.address), Priority.FOREGROUND) }.getOrNull()
            }
        }
    }

    BackHandler {
        when (val current = step) {
            SolanaSendStep.Form -> navController.popBackStack()
            SolanaSendStep.ScanRecipient -> step = SolanaSendStep.Form
            is SolanaSendStep.Review -> {
                notice = null
                step = SolanaSendStep.Form
            }
            is SolanaSendStep.SetupReview -> {
                notice = null
                step = SolanaSendStep.Form
            }
            is SolanaSendStep.Request -> {
                notice = null
                step = if (current.setup) SolanaSendStep.Form else SolanaSendStep.Review(current.plan)
            }
            is SolanaSendStep.Scanning -> step = SolanaSendStep.Request(current.plan, current.parts, current.setup)
            is SolanaSendStep.Submit -> when (current.machine.state.value) {
                SubmitState.Submitting -> Unit
                is SubmitState.Sent -> if (current.setup) finishSetup() else navController.popBackStack()
                else -> {
                    notice = null
                    step = SolanaSendStep.Request(current.plan, current.parts, current.setup)
                }
            }
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
        is SolanaSendStep.SetupReview -> SolanaSetupReviewScreen(
            network = network,
            sender = from.address,
            nonceAccount = current.nonceAccount,
            rentLamports = current.rentLamports,
            notice = notice,
            onConfirm = { prepareSetup(current) },
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
                step = if (current.setup) SolanaSendStep.Form else SolanaSendStep.Review(current.plan)
            },
        )
        is SolanaSendStep.Scanning -> ScanScreen(
            title = "Scan signature",
            progress = scanProgress,
            statusText = scanStatus,
            onText = { text -> onSignatureText(current, text) },
            onCancel = { step = SolanaSendStep.Request(current.plan, current.parts, current.setup) },
        )
        is SolanaSendStep.Submit -> SubmitScreen(
            machine = current.machine,
            rows = solanaSummary(current.plan),
            explorerUrl = { signature -> SolanaSend.explorerTxUrl(current.plan.network, signature) },
            status = solanaOutcomeText(current.plan.network, sentOutcome),
            explorerNotice = sentNotice,
            onSubmit = { submit(current) },
            onOpenExplorer = { signature -> openExplorer(SolanaSend.explorerTxUrl(current.plan.network, signature)) },
            onStartOver = {
                notice = null
                step = SolanaSendStep.Form
            },
            onBack = {
                notice = null
                step = SolanaSendStep.Request(current.plan, current.parts, current.setup)
            },
            onDone = { if (current.setup) finishSetup() else navController.popBackStack() },
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

/**
 * The words of a failed Solana submission. A blockhash the network no longer accepts can never be sent again, so the owner starts the send over
 * and the screen offers no retry.
 */
private fun solanaFailure(error: Throwable): SubmitFailure {
    val expired = error is ProviderError.Fatal && error.message == JsonRpc.BLOCKHASH_EXPIRED_MESSAGE
    return if (expired) {
        SubmitFailure(JsonRpc.BLOCKHASH_EXPIRED_MESSAGE, retryable = false)
    } else {
        SubmitFailure(ownerWords(error, BROADCAST_FAILED))
    }
}
