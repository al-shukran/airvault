package app.lensly.ui.flow

import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.chains.evm.signer.UndecodedCall
import app.airvault.core.model.Approval
import app.airvault.core.model.Review as CoreReview
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.KeyScopeViolation
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ScannedRequest
import app.lensly.app.sign.WalletMatch
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.routeScan
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.UNSUPPORTED_CODE
import app.lensly.core.parse.WireException
import app.lensly.core.wallet.AccountAddresses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the sign flow is. The screen shows one step at a time. */
sealed interface SignStep {
    /** Waiting for the request. [progress] runs from 0 to 1 for an animated request, and is null for a single code. */
    data class Scanning(val progress: Float?, val status: String) : SignStep

    /**
     * The request cannot be signed. [reason] says why, in plain words. [call] is set when the request is a contract call Lensly
     * cannot decode, so the screen can show all of it.
     */
    data class Refused(val reason: String, val call: UndecodedCall? = null) : SignStep

    /** The request passed every check. The owner reads the review, then approves or rejects it. */
    data class Review(val ready: Prepared.Ready) : SignStep

    /** A request of a chain app on the one sign flow passed its checks. The owner ticks the checks the review names, then approves or rejects it. */
    data class AppReview(val ready: AppPrepared.Ready) : SignStep

    /**
     * The hold-to-sign of an approved request of a chain app ran. [approval] is the owner's approval of [ready], which the signature needs. The
     * App Password is checked before anything is signed. [busy] while that check runs.
     */
    data class AppConfirming(val ready: AppPrepared.Ready, val approval: Approval, val busy: Boolean) : SignStep

    /** The hold-to-sign control ran. The App Password is checked before anything is signed. [busy] while that check runs. */
    data class Confirming(val ready: Prepared.Ready, val busy: Boolean) : SignStep

    /** The signature, as the frames to show the online wallet. */
    data class Signature(val parts: List<String>) : SignStep

    /** The flow is over. The screen goes back to the wallet. */
    data object Closed : SignStep
}

/** The wallet cannot be read right now: its vault is closed or damaged, or its record is missing. */
class WalletUnavailable : Exception("The wallet could not be read.")

/** A Review Record could not be written. The signature it was for is then not shown. */
class ReviewNotSaved : Exception("The review could not be saved.")

/** What [SignFlow] asks of the app. [VaultSignPorts] is the app's implementation. Tests use fakes. */
interface SignFlowPorts {
    /** Every wallet of the vault, as the sign pipeline checks them. Throws [WalletUnavailable] when the vault cannot be read. */
    suspend fun wallets(): List<WalletView>

    /**
     * Parses the scanned [parts] of one request, in the order they were scanned, in the Parser Sandbox. Never throws: a
     * sandbox that does not answer is a refusal.
     */
    suspend fun scan(parts: List<String>): ScanReply

    /** Checks [parsed] against wallet [walletId] and the registry. Throws [WalletUnavailable] when the wallet cannot be read. */
    suspend fun prepare(walletId: String, parsed: ParsedRequest): Prepared

    /** The 32-byte private key of account [accountIndex] of wallet [walletId]. The caller zeroes it. Throws [WalletUnavailable]. */
    suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray

    /** Checks the App Password. The caller keeps ownership of [password] and zeroes it afterwards. */
    suspend fun checkPassword(password: CharArray): PasswordCheck

    /**
     * Signs [ready] with [privateKey] and returns the ur:eth-signature frames. Takes ownership of [privateKey] and zeroes
     * it. Throws IllegalArgumentException when the signer refuses the data.
     */
    suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String>

    /** Writes [entry] as a Review Record of its wallet. Throws [ReviewNotSaved] when the record cannot be written. */
    suspend fun record(entry: ReviewEntry)

    /**
     * Reviews the request [request] of an installed chain app against wallet [walletId]: the app's rules, the wallet's keys and the master
     * fingerprint the request names. Throws [WalletUnavailable] when the wallet cannot be read.
     */
    suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared

    /**
     * Signs the approved request [ready] through its app's `SignerApp.sign`, with the wallet's key access scoped to the app's key scopes, and
     * returns the frames of the response UR. Throws [WalletUnavailable] when the wallet cannot be read, and [KeyScopeViolation] when the app asks
     * for a key outside its scope.
     */
    suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String>

    /** Writes the [entry] of a chain app as a Review Record of its wallet. Throws [ReviewNotSaved] when the record cannot be written. */
    suspend fun recordApp(entry: AppReviewEntry)
}

/**
 * The sign flow as a state machine. It reads a scanned request through the Parser Sandbox, checks it, shows the review, checks the App
 * Password again before it signs, and emits the signature frames. It has no Android types. [SignFlowPorts] does the vault and registry work.
 *
 * A flow for one wallet ([walletId] set) checks the request against that wallet. A flow with no [walletId] finds the wallet
 * the request is for among all the wallets of the vault, and refuses to choose when none or several are.
 *
 * Each signature, and each refusal of a request that has a wallet, is written as a Review Record through [SignFlowPorts.record] for an
 * Ethereum request, and through [SignFlowPorts.recordApp] for a request of a chain app. A reviewed request that is left unsigned is
 * recorded as cancelled.
 *
 * [state] holds the current step. Calls that suspend run one at a time. [leave] ends the flow at once, and any call that
 * is still running then changes nothing.
 */
class SignFlow(
    private val ports: SignFlowPorts,
    private val walletId: String?,
) {
    /** The distinct texts the sandbox accepted as parts of the request, in the order they were scanned. */
    private val parts = LinkedHashSet<String>()
    private var scannedChars = 0L
    private var progress: Float? = null
    private val gate = Mutex()

    /** Raised by [leave]. A call that started under an earlier value must not change the state. */
    private var epoch = 0

    private val current = MutableStateFlow<SignStep>(SignStep.Scanning(progress = null, status = SCAN_HINT))

    /** The checks ticked on the review on screen, by label. Approve waits until every check the review names is ticked. */
    private val checks = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<SignStep> = current.asStateFlow()

    /** The checks ticked on the review on screen, as the screen shows them. */
    val ticked: StateFlow<Set<String>> = checks.asStateFlow()

    /**
     * Feeds one scanned QR text. Only texts that belong to the request count, and each distinct text counts once, so a
     * looping animation cannot use up the size limit.
     */
    suspend fun onScanned(text: String) {
        gate.withLock {
            if (current.value !is SignStep.Scanning) return
            val started = epoch
            if (text in parts) {
                current.value = scanning(READING)
                return
            }
            val reply = ports.scan(parts.toList() + text)
            if (started != epoch) return
            when (reply) {
                is ScanReply.Refused -> {
                    val request = reply.request
                    if (request == null) {
                        current.value = SignStep.Refused(reply.reason)
                    } else if (accept(text)) {
                        publish(started, refusedApp(started, request, reply.reason))
                    }
                }
                ScanReply.NotPart -> current.value = scanning(NOT_PART)
                is ScanReply.Incomplete -> if (accept(text)) {
                    progress = reply.progress.toFloat()
                    current.value = scanning(READING)
                }
                is ScanReply.App -> if (accept(text)) publish(started, decideApp(started, reply.request))
            }
        }
    }

    /** Counts [text] as a part of the request. False when the request is then over the size limit, which refuses it. */
    private fun accept(text: String): Boolean {
        parts += text
        scannedChars += text.length
        if (scannedChars > MAX_SCAN_CHARS) current.value = SignStep.Refused(TOO_LARGE)
        return scannedChars <= MAX_SCAN_CHARS
    }

    /**
     * APPROVE on the review. The gate is the review's own (spec 3.2 and 8.3, [Approval]): every check it names must be ticked, and no
     * warning may be a block. Until then nothing changes, whatever the screen shows. The App Password is asked before anything is signed.
     */
    fun approve() {
        val step = current.value
        val review = reviewOf(step) ?: return
        val approval = Approval.of(review, checks.value) ?: return
        current.value = when (step) {
            is SignStep.Review -> SignStep.Confirming(step.ready, busy = false)
            is SignStep.AppReview -> SignStep.AppConfirming(step.ready, approval, busy = false)
            else -> return
        }
    }

    /** The owner ticked, or unticked, one check of the review on screen. Nothing changes when no review is on screen. */
    fun tick(label: String, on: Boolean) {
        if (reviewOf(current.value) == null) return
        checks.value = if (on) checks.value + label else checks.value - label
    }

    /**
     * The owner typed the App Password. A wrong one leaves the step as it was, with nothing shown. A right one signs the
     * reviewed request. [password] is zeroed before this returns.
     */
    suspend fun confirm(password: CharArray) {
        try {
            gate.withLock {
                val app = current.value as? SignStep.AppConfirming
                if (app != null) {
                    confirmApp(app, password)
                    return
                }
                val waiting = current.value as? SignStep.Confirming ?: return
                if (waiting.busy) return
                val started = epoch
                current.value = waiting.copy(busy = true)
                val next = when (ports.checkPassword(password)) {
                    PasswordCheck.Accepted -> signApproved(waiting.ready, started)
                    PasswordCheck.Rejected -> SignStep.Confirming(waiting.ready, busy = false)
                    PasswordCheck.Unavailable -> refusal(
                        started = started,
                        wallet = waiting.ready.walletId,
                        request = waiting.ready.request,
                        reason = VAULT_UNCHECKED,
                        ready = waiting.ready,
                    )
                }
                publish(started, next)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** The system back gesture. On the password step it returns to the review. Anywhere else it ends the flow. */
    suspend fun back() {
        when (current.value) {
            is SignStep.Confirming -> cancelApproval()
            is SignStep.AppConfirming -> cancelAppApproval()
            SignStep.Closed -> Unit
            else -> leave()
        }
    }

    /**
     * REJECT, DONE, CANCEL, or leaving the flow. Work still running is discarded when it finishes. Leaving a reviewed request
     * before it is signed records it as cancelled.
     */
    suspend fun leave() {
        val reviewed = when (val step = current.value) {
            is SignStep.Review -> step.ready
            is SignStep.Confirming -> step.ready
            else -> null
        }
        val app = when (val step = current.value) {
            is SignStep.AppReview -> step.ready
            is SignStep.AppConfirming -> step.ready
            else -> null
        }
        epoch++
        current.value = SignStep.Closed
        checks.value = emptySet()
        reviewed?.let {
            recordQuietly(ReviewEntry(it.walletId, ReviewOutcome.CANCELLED, it.request, it, refusal = null))
        }
        app?.let {
            recordAppQuietly(AppReviewEntry(it.walletId, ReviewOutcome.CANCELLED, it.request, it, acknowledged = emptyList(), refusal = null))
        }
    }

    private fun cancelApproval() {
        val waiting = current.value as? SignStep.Confirming ?: return
        if (!waiting.busy) current.value = SignStep.Review(waiting.ready)
    }

    /**
     * Reviews a complete request that an installed chain app parsed. EVM keeps its own steps until it moves to the one sign flow (WP15). Every
     * other app goes through [decideGeneric].
     */
    private suspend fun decideApp(started: Int, request: AppRequest): SignStep = when (request.header.app) {
        evmManifest.id -> decideEvm(started, request.body)
        else -> decideGeneric(started, request)
    }

    /** Reviews a complete EVM request. Its body is the parse the EVM app made in the Parser Sandbox, read back under the wire's checks. */
    private suspend fun decideEvm(started: Int, body: ByteArray): SignStep {
        val parsed = try {
            EvmRequestBody.decode(body)
        } catch (e: WireException) {
            return SignStep.Refused(CANNOT_BE_READ)
        }
        return decide(started, parsed)
    }

    /** Reviews a complete request of a chain app on the one sign flow, for the wallet it is for. A refusal is recorded under that wallet. */
    private suspend fun decideGeneric(started: Int, request: AppRequest): SignStep =
        routed(ScannedRequest.App(request.header)) { target ->
            try {
                when (val prepared = ports.appPrepare(target, request)) {
                    is AppPrepared.Ready -> SignStep.AppReview(prepared)
                    is AppPrepared.Refused -> appRefusal(started, target, request, prepared.reason, ready = null)
                }
            } catch (e: WalletUnavailable) {
                appRefusal(started, target, request, WALLET_UNREADABLE, ready = null)
            }
        }

    /**
     * The step for a request that an app refused while it parsed it, on a rule of its own. The refusal is recorded under the wallet the request
     * is for, as a refusal after parsing is. Nothing is signed for it.
     */
    private suspend fun refusedApp(started: Int, request: AppRequest, reason: String): SignStep =
        routed(ScannedRequest.App(request.header)) { target -> appRefusal(started, target, request, reason, ready = null) }

    /** Checks the App Password for an approved request of a chain app, then signs it. The caller zeroes [password]. */
    private suspend fun confirmApp(waiting: SignStep.AppConfirming, password: CharArray) {
        if (waiting.busy) return
        val started = epoch
        current.value = waiting.copy(busy = true)
        val next = when (ports.checkPassword(password)) {
            PasswordCheck.Accepted -> signApp(waiting, started)
            PasswordCheck.Rejected -> SignStep.AppConfirming(waiting.ready, waiting.approval, busy = false)
            PasswordCheck.Unavailable ->
                appRefusal(started, waiting.ready.walletId, waiting.ready.request, VAULT_UNCHECKED, waiting.ready)
        }
        publish(started, next)
    }

    /**
     * Signs the approved request through its app. A refusal, or a wallet that cannot be read, signs nothing and is shown as the reason. The
     * signature is recorded before it is returned, so no signature is shown without its record.
     */
    private suspend fun signApp(waiting: SignStep.AppConfirming, started: Int): SignStep {
        if (started != epoch) return SignStep.Closed
        val ready = waiting.ready
        val parts = try {
            ports.appSign(ready, waiting.approval)
        } catch (e: WalletUnavailable) {
            return appRefusal(started, ready.walletId, ready.request, WALLET_UNREADABLE, ready)
        } catch (e: KeyScopeViolation) {
            // The key scope refused the path before any key was derived. The refusal is recorded (spec 2.2 rule 6).
            return appRefusal(started, ready.walletId, ready.request, OUT_OF_SCOPE, ready)
        } catch (e: IllegalArgumentException) {
            return appRefusal(started, ready.walletId, ready.request, e.message ?: SIGN_REFUSED, ready)
        }
        return try {
            if (started != epoch) return SignStep.Closed
            val signed = AppReviewEntry(ready.walletId, ReviewOutcome.SIGNED, ready.request, ready, waiting.approval.acknowledged, null)
            ports.recordApp(signed)
            SignStep.Signature(parts)
        } catch (e: ReviewNotSaved) {
            SignStep.Refused(NOT_SAVED)
        }
    }

    /**
     * The step that shows a refusal of a request of a chain app, recorded under [wallet] with the [ready] review it came after, if any. A refusal
     * decided after the flow was left was never shown, so it is not recorded either.
     */
    private suspend fun appRefusal(
        started: Int,
        wallet: String,
        request: AppRequest,
        reason: String,
        ready: AppPrepared.Ready?,
    ): SignStep.Refused {
        if (started == epoch) recordAppQuietly(AppReviewEntry(wallet, ReviewOutcome.REFUSED, request, ready, emptyList(), reason))
        return SignStep.Refused(reason)
    }

    /** Writes the entry of a chain app. A write that fails is left out, as the other Review Records are. */
    private suspend fun recordAppQuietly(entry: AppReviewEntry) {
        try {
            ports.recordApp(entry)
        } catch (e: ReviewNotSaved) {
            // Left out: the screen already shows the outcome, and nothing is signed for it.
        }
    }

    private fun cancelAppApproval() {
        val waiting = current.value as? SignStep.AppConfirming ?: return
        if (!waiting.busy) current.value = SignStep.AppReview(waiting.ready)
    }

    /** Checks a complete request. Only the first complete request is decided, because scanning stops once it is. */
    private suspend fun decide(started: Int, parsed: ParsedRequest): SignStep {
        val request = parsed.request
        if (request.requestId == null) {
            return refusal(started = started, wallet = walletId, request = request, reason = NO_REQUEST_ID)
        }
        return routed(ScannedRequest.Ethereum(request)) { target ->
            try {
                when (val prepared = ports.prepare(target, parsed)) {
                    is Prepared.Ready -> SignStep.Review(prepared)
                    is Prepared.Refused -> refusal(
                        started = started,
                        wallet = target,
                        request = request,
                        reason = prepared.reason,
                        call = prepared.call,
                    )
                }
            } catch (e: WalletUnavailable) {
                refusal(started = started, wallet = target, request = request, reason = WALLET_UNREADABLE)
            }
        }
    }

    /**
     * Runs [decide] for the wallet that a scanned [request] is for. A flow with its own wallet decides for that wallet. A scan of any
     * wallet decides for the one wallet of the vault the request is for, and refuses in plain words when none, or several, are. Nothing
     * is recorded for a request that no wallet is for.
     */
    private suspend fun routed(request: ScannedRequest, decide: suspend (String) -> SignStep): SignStep {
        val target = walletId ?: try {
            when (val match = routeScan(request, ports.wallets())) {
                is WalletMatch.One -> match.walletId
                WalletMatch.None -> return SignStep.Refused(NO_WALLET)
                is WalletMatch.Several -> return SignStep.Refused(SEVERAL_WALLETS)
            }
        } catch (e: WalletUnavailable) {
            return SignStep.Refused(WALLET_UNREADABLE)
        }
        return decide(target)
    }

    /**
     * Loads the key of the reviewed account and checks that it controls the address on the review. Only then does it sign.
     * The key is zeroed on every path. The signature is recorded before it is returned, so no signature is shown without its
     * record. Returns [SignStep.Closed] when the flow was left in the meantime, which [publish] then ignores.
     */
    private suspend fun signApproved(ready: Prepared.Ready, started: Int): SignStep {
        if (started != epoch) return SignStep.Closed
        val key = try {
            ports.privateKey(ready.walletId, ready.accountIndex)
        } catch (e: WalletUnavailable) {
            return refusal(started, ready.walletId, ready.request, WALLET_UNREADABLE, ready)
        }
        try {
            if (started != epoch) return SignStep.Closed
            val keyAddress = AccountAddresses.addressOfPrivateKey(key)
            if (!keyAddress.equals(ready.signer.address, ignoreCase = true)) {
                return refusal(started, ready.walletId, ready.request, KEY_MISMATCH, ready)
            }
            val parts = try {
                ports.sign(ready, key)
            } catch (e: IllegalArgumentException) {
                return refusal(started, ready.walletId, ready.request, e.message ?: SIGN_REFUSED, ready)
            }
            return try {
                if (started != epoch) return SignStep.Closed
                ports.record(ReviewEntry(ready.walletId, ReviewOutcome.SIGNED, ready.request, ready, refusal = null))
                SignStep.Signature(parts)
            } catch (e: ReviewNotSaved) {
                refusal(started, ready.walletId, ready.request, NOT_SAVED, ready)
            }
        } finally {
            key.fill(0)
        }
    }

    /**
     * The step that shows a refusal of [request]. It is recorded under [wallet], with the [ready] review it came after if any. A
     * request that no wallet was chosen for has nothing to be recorded under. A refusal decided after the flow was left was never
     * shown, so it is not recorded either.
     */
    private suspend fun refusal(
        started: Int,
        wallet: String?,
        request: EthSignRequest,
        reason: String,
        ready: Prepared.Ready? = null,
        call: UndecodedCall? = null,
    ): SignStep.Refused {
        if (wallet != null && started == epoch) recordQuietly(ReviewEntry(wallet, ReviewOutcome.REFUSED, request, ready, reason))
        return SignStep.Refused(reason, call)
    }

    /**
     * Writes [entry]. A write that fails is left out. The outcome was decided already, and a refusal or a cancel signs nothing.
     */
    private suspend fun recordQuietly(entry: ReviewEntry) {
        try {
            ports.record(entry)
        } catch (e: ReviewNotSaved) {
            // Left out: the screen already shows the outcome, and nothing is signed for it.
        }
    }

    private fun scanning(status: String): SignStep.Scanning = SignStep.Scanning(progress, status)

    /** Shows [step] while its flow is still the one that started it. A review starts with no check ticked. */
    private fun publish(started: Int, step: SignStep) {
        if (started != epoch) return
        if (reviewOf(step) != null) checks.value = emptySet()
        current.value = step
    }

    /** The review the owner reads on [step], or null when [step] is not a review step. */
    private fun reviewOf(step: SignStep): CoreReview? = when (step) {
        is SignStep.Review -> step.ready.review
        is SignStep.AppReview -> step.ready.review
        else -> null
    }

    private companion object {
        /**
         * The most scanned text the flow accepts, in characters. A UR in bytewords uses two characters per byte, and an
         * animated request repeats frames when the camera misses some. A 16 KiB calldata request reads in about 35 K
         * characters when every frame arrives, and in about 65 K when half of them are missed, so this leaves room for both.
         */
        const val MAX_SCAN_CHARS = 128L * 1024

        const val SCAN_HINT = "Scan the sign request from your online wallet."
        const val READING = "Reading the request. Keep the code in view."
        const val NOT_PART = "That code is not part of this request."
        val TOO_LARGE = "The request is larger than ${MAX_SCAN_CHARS / 1024} KiB."
        const val NO_REQUEST_ID = "Request has no ID"
        const val KEY_MISMATCH = "Key does not match account"
        const val WALLET_UNREADABLE = "The wallet could not be read. Nothing was signed."
        const val VAULT_UNCHECKED = "The Application Password could not be checked. Nothing was signed."
        const val NOT_SAVED = "The review could not be saved, so the signature is not shown."
        const val OUT_OF_SCOPE = "The request asks for a key Lensly does not sign with for this chain. Nothing was signed."
        const val SIGN_REFUSED = "The request could not be signed."
        const val NO_WALLET = "No wallet in this vault can sign this request"
        const val SEVERAL_WALLETS = "More than one wallet in this vault can sign this request. Lensly will not guess which one signs."
    }
}
