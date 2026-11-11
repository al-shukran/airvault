package app.lensly.ui.flow

import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WalletMatch
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.matchWallet
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.urPartRefusal
import app.lensly.core.wallet.AccountAddresses
import com.sparrowwallet.hummingbird.UR
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

    /**
     * A scan of any wallet found no wallet for [request], or [several] wallets for it. Lensly does not choose between them,
     * so the owner scans again.
     */
    data class NoMatchingWallet(val request: EthSignRequest, val several: Boolean) : SignStep

    /** The request passed every check. The owner reads the review, then approves or rejects it. */
    data class Review(val ready: Prepared.Ready) : SignStep

    /** The hold-to-sign control ran. The App Password is checked before anything is signed. [busy] while that check runs. */
    data class Confirming(val ready: Prepared.Ready, val busy: Boolean) : SignStep

    /** The signature, as the frames to show the online wallet. */
    data class Signature(val parts: List<String>) : SignStep

    /** The flow is over. The screen goes back to the wallet. */
    data object Closed : SignStep
}

/** The result of checking the App Password. */
enum class PasswordCheck {
    /** The password is right. The check opened a session only to prove it, and that session is already closed. */
    Accepted,

    /** The password is wrong, or a failure delay is running. The owner cannot tell which. */
    Rejected,

    /** The vault could not be checked, for example because its stored state is damaged. */
    Unavailable,
}

/** The wallet cannot be read right now: its vault is closed or damaged, or its record is missing. */
class WalletUnavailable : Exception("The wallet could not be read.")

/** A Review Record could not be written. The signature it was for is then not shown. */
class ReviewNotSaved : Exception("The review could not be saved.")

/** What [SignFlow] asks of the app. [VaultSignPorts] is the app's implementation. Tests use fakes. */
interface SignFlowPorts {
    /** Every wallet of the vault, as the sign pipeline checks them. Throws [WalletUnavailable] when the vault cannot be read. */
    suspend fun wallets(): List<WalletView>

    /** Checks [request] against wallet [walletId] and the registry. Throws [WalletUnavailable] when the wallet cannot be read. */
    suspend fun prepare(walletId: String, request: EthSignRequest): Prepared

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
}

/**
 * The sign flow as a state machine. It reads a scanned ur:eth-sign-request, checks it, shows the review, checks the App
 * Password again before it signs, and emits the signature frames. It has no Android types. [SignFlowPorts] does the vault
 * and registry work.
 *
 * A flow for one wallet ([walletId] set) checks the request against that wallet. A flow with no [walletId] finds the wallet
 * the request is for among all the wallets of the vault, and refuses to choose when none or several are.
 *
 * Each signature, and each refusal of a request that has a wallet, is written as a Review Record through [SignFlowPorts.record].
 * A reviewed request that is left unsigned is recorded as cancelled.
 *
 * [state] holds the current step. Calls that suspend run one at a time. [leave] ends the flow at once, and any call that
 * is still running then changes nothing.
 */
class SignFlow(
    private val ports: SignFlowPorts,
    private val walletId: String?,
) {
    private var scanner = UrScanner()
    private val distinctParts = HashSet<String>()
    private var scannedChars = 0L
    private val gate = Mutex()

    /** Raised by [leave]. A call that started under an earlier value must not change the state. */
    private var epoch = 0

    private val current = MutableStateFlow<SignStep>(SignStep.Scanning(progress = null, status = SCAN_HINT))

    val state: StateFlow<SignStep> = current.asStateFlow()

    /**
     * Feeds one scanned QR text. Only texts that belong to the request count, and each distinct text counts once, so a
     * looping animation cannot use up the size limit.
     */
    suspend fun onScanned(text: String) {
        gate.withLock {
            if (current.value !is SignStep.Scanning) return
            val started = epoch
            val refusal = urPartRefusal(text)
            if (refusal != null) {
                current.value = SignStep.Refused(refusal)
                return
            }
            if (!scanner.receive(text)) {
                current.value = scanning(NOT_PART)
                return
            }
            if (distinctParts.add(text)) {
                scannedChars += text.length
                if (scannedChars > MAX_SCAN_CHARS) {
                    current.value = SignStep.Refused(TOO_LARGE)
                    return
                }
            }
            val ur = scanner.result()
            if (ur == null) {
                current.value = scanning(READING)
                return
            }
            publish(started, decide(started, ur))
        }
    }

    /** SCAN AGAIN after a request matched no wallet, or several. The scan starts from nothing. */
    fun scanAgain() {
        if (current.value !is SignStep.NoMatchingWallet) return
        scanner = UrScanner()
        distinctParts.clear()
        scannedChars = 0L
        current.value = SignStep.Scanning(progress = null, status = SCAN_HINT)
    }

    /** APPROVE on the review. The App Password is asked before anything is signed. */
    fun approve() {
        val reviewed = current.value as? SignStep.Review ?: return
        current.value = SignStep.Confirming(reviewed.ready, busy = false)
    }

    /**
     * The owner typed the App Password. A wrong one leaves the step as it was, with nothing shown. A right one signs the
     * reviewed request. [password] is zeroed before this returns.
     */
    suspend fun confirm(password: CharArray) {
        try {
            gate.withLock {
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
        epoch++
        current.value = SignStep.Closed
        reviewed?.let {
            recordQuietly(ReviewEntry(it.walletId, ReviewOutcome.CANCELLED, it.request, it, refusal = null))
        }
    }

    private fun cancelApproval() {
        val waiting = current.value as? SignStep.Confirming ?: return
        if (!waiting.busy) current.value = SignStep.Review(waiting.ready)
    }

    /** Checks a complete request. Only the first complete request is decided, because scanning stops once it is. */
    private suspend fun decide(started: Int, ur: UR): SignStep {
        if (ur.type != EthSignRequest.UR_TYPE) return SignStep.Refused(UNSUPPORTED)
        val request = when (val decoded = EthSignRequest.decode(ur)) {
            is EthSignRequest.Result.Ok -> decoded.request
            is EthSignRequest.Result.Rejected -> return SignStep.Refused(decoded.reason)
        }
        if (request.requestId == null) {
            return refusal(started = started, wallet = walletId, request = request, reason = NO_REQUEST_ID)
        }
        return try {
            val target = walletId ?: when (val match = matchWallet(request, ports.wallets())) {
                is WalletMatch.One -> match.walletId
                WalletMatch.None -> return SignStep.NoMatchingWallet(request, several = false)
                is WalletMatch.Several -> return SignStep.NoMatchingWallet(request, several = true)
            }
            when (val prepared = ports.prepare(target, request)) {
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
            SignStep.Refused(WALLET_UNREADABLE)
        }
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
            if (!keyAddress.equals(ready.review.fromAddress, ignoreCase = true)) {
                return refusal(started, ready.walletId, ready.request, KEY_MISMATCH, ready)
            }
            val parts = try {
                ports.sign(ready, key)
            } catch (e: IllegalArgumentException) {
                return refusal(started, ready.walletId, ready.request, e.message ?: SIGN_REFUSED, ready)
            }
            return try {
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

    private fun scanning(status: String): SignStep.Scanning = SignStep.Scanning(
        progress = if (distinctParts.isEmpty()) null else scanner.progress.toFloat(),
        status = status,
    )

    private fun publish(started: Int, step: SignStep) {
        if (started == epoch) current.value = step
    }

    private companion object {
        /** The most scanned text the flow accepts, in characters. A UR in bytewords uses two characters per byte. */
        const val MAX_SCAN_CHARS = 64L * 1024

        const val SCAN_HINT = "Scan the sign request from your online wallet."
        const val READING = "Reading the request. Keep the code in view."
        const val NOT_PART = "That code is not part of this request."
        const val TOO_LARGE = "The request is larger than 64 KiB."
        const val UNSUPPORTED = "Unsupported code"
        const val NO_REQUEST_ID = "Request has no ID"
        const val KEY_MISMATCH = "Key does not match account"
        const val WALLET_UNREADABLE = "The wallet could not be read. Nothing was signed."
        const val VAULT_UNCHECKED = "The App Password could not be checked. Nothing was signed."
        const val NOT_SAVED = "The review could not be saved, so the signature is not shown."
        const val SIGN_REFUSED = "The request could not be signed."
    }
}
