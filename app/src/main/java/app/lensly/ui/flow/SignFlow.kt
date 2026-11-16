package app.lensly.ui.flow

import app.lensly.app.BitcoinReviewed
import app.lensly.app.sign.AlgorandReviewEntry
import app.lensly.app.sign.BitcoinReviewEntry
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.SolanaReviewEntry
import app.lensly.app.sign.UndecodedCall
import app.lensly.app.sign.WalletMatch
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.matchWallet
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.btc.BtcRefusedException
import app.lensly.core.parse.ParsedRequest
import app.lensly.core.parse.ScanReply
import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.ur.AlgoSignRequest
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.wallet.AccountAddresses
import fr.acinq.bitcoin.psbt.Psbt
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

    /**
     * A Bitcoin request passed its review checks. The owner reads it, then approves or rejects it. [confirmed] is set once the owner has
     * checked the unverified input amounts, which the review asks for before it can be signed.
     */
    data class BitcoinReview(val ready: BitcoinPrepared.Ready, val confirmed: Boolean) : SignStep

    /** The Bitcoin hold-to-sign ran. The App Password is checked before anything is signed. [busy] while that check runs. */
    data class BitcoinConfirming(val ready: BitcoinPrepared.Ready, val confirmed: Boolean, val busy: Boolean) : SignStep

    /** A Solana request passed its checks. The owner reads the review, then approves or rejects it. */
    data class SolanaReview(val ready: SolanaPrepared.Ready) : SignStep

    /** The Solana hold-to-sign ran. The App Password is checked before anything is signed. [busy] while that check runs. */
    data class SolanaConfirming(val ready: SolanaPrepared.Ready, val busy: Boolean) : SignStep

    /** An Algorand request passed its checks. The owner reads the review, then approves or rejects it. */
    data class AlgorandReview(val ready: AlgorandPrepared.Ready) : SignStep

    /** The Algorand hold-to-sign ran. The App Password is checked before anything is signed. [busy] while that check runs. */
    data class AlgorandConfirming(val ready: AlgorandPrepared.Ready, val busy: Boolean) : SignStep

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

/** The outcome of reviewing a Bitcoin request against a wallet: the review the owner reads, or the reason it is refused. */
sealed interface BitcoinPrepared {
    /** The request spends from one of the wallet's Bitcoin accounts and passed every rule. [psbt] is the parsed request. */
    data class Ready(val walletId: String, val psbt: Psbt, val reviewed: BitcoinReviewed) : BitcoinPrepared

    /** The request cannot be signed. [reason] says why, in plain words. Nothing is signed for it. */
    data class Refused(val reason: String) : BitcoinPrepared
}

/** The outcome of reviewing a Solana request against a wallet: the review the owner reads, or the reason it is refused. */
sealed interface SolanaPrepared {
    /** The request names an account of the wallet and passed every rule. [request] is the parsed sol-sign-request. */
    data class Ready(val walletId: String, val request: SolSignRequest, val review: SolanaRequestReview) : SolanaPrepared

    /** The request cannot be signed. [reason] says why, in plain words. Nothing is signed for it. */
    data class Refused(val reason: String) : SolanaPrepared
}

/** The outcome of reviewing an Algorand request against a wallet: the review the owner reads, or the reason it is refused. */
sealed interface AlgorandPrepared {
    /** The request names an account of the wallet and passed every rule. [request] is the parsed algo-sign-request. */
    data class Ready(val walletId: String, val request: AlgoSignRequest, val review: AlgorandRequestReview) : AlgorandPrepared

    /** The request cannot be signed. [reason] says why, in plain words. Nothing is signed for it. */
    data class Refused(val reason: String) : AlgorandPrepared
}

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

    /** Writes the Bitcoin [entry] as a Review Record of its wallet. Throws [ReviewNotSaved] when the record cannot be written. */
    suspend fun recordBitcoin(entry: BitcoinReviewEntry)

    /**
     * Reviews the Bitcoin request [psbt], the bytes the Parser Sandbox parsed, against wallet [walletId] with the owner's Bitcoin
     * policies. Throws [WalletUnavailable] when the wallet cannot be read.
     */
    suspend fun bitcoinPrepare(walletId: String, psbt: ByteArray): BitcoinPrepared

    /**
     * Signs [ready] with the Bitcoin account it was reviewed under, and returns the signed PSBT as the frames of its `ur:crypto-psbt`.
     * Takes the seed and zeroes it. Throws [WalletUnavailable] when the wallet cannot be read, and [BtcRefusedException] when a rule
     * refuses the signing.
     */
    suspend fun bitcoinSign(ready: BitcoinPrepared.Ready, unverifiedConfirmed: Boolean): List<String>

    /**
     * Reviews the Solana [request] against wallet [walletId]: its Solana accounts, its master fingerprint and the rules of the request.
     * Throws [WalletUnavailable] when the wallet cannot be read.
     */
    suspend fun solanaPrepare(walletId: String, request: SolSignRequest): SolanaPrepared

    /**
     * Signs the reviewed Solana request with the account it was reviewed under, and returns the `ur:sol-signature` frames. Throws
     * [WalletUnavailable] when the wallet cannot be read, and IllegalArgumentException when the wallet's key is not the account's key.
     */
    suspend fun solanaSign(ready: SolanaPrepared.Ready): List<String>

    /** Writes the Solana [entry] as a Review Record of its wallet. Throws [ReviewNotSaved] when the record cannot be written. */
    suspend fun recordSolana(entry: SolanaReviewEntry)

    /**
     * Reviews the Algorand [request] against wallet [walletId]: its Algorand accounts, its master fingerprint and the rules of the request.
     * Throws [WalletUnavailable] when the wallet cannot be read.
     */
    suspend fun algorandPrepare(walletId: String, request: AlgoSignRequest): AlgorandPrepared

    /**
     * Signs the reviewed Algorand request with the account it was reviewed under, and returns the `ur:algo-signature` frames. Throws
     * [WalletUnavailable] when the wallet cannot be read, and IllegalArgumentException when the wallet's key is not the account's key.
     */
    suspend fun algorandSign(ready: AlgorandPrepared.Ready): List<String>

    /** Writes the Algorand [entry] as a Review Record of its wallet. Throws [ReviewNotSaved] when the record cannot be written. */
    suspend fun recordAlgorand(entry: AlgorandReviewEntry)
}

/**
 * The sign flow as a state machine. It reads a scanned ur:eth-sign-request through the Parser Sandbox, checks it, shows the review, checks the App
 * Password again before it signs, and emits the signature frames. It has no Android types. [SignFlowPorts] does the vault
 * and registry work.
 *
 * A flow for one wallet ([walletId] set) checks the request against that wallet. A flow with no [walletId] finds the wallet
 * the request is for among all the wallets of the vault, and refuses to choose when none or several are.
 *
 * Each signature, and each refusal of a request that has a wallet, is written as a Review Record through [SignFlowPorts.record], and
 * a Bitcoin request is written through [SignFlowPorts.recordBitcoin] the same way. A reviewed request that is left unsigned is
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

    val state: StateFlow<SignStep> = current.asStateFlow()

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
                is ScanReply.Refused -> current.value = SignStep.Refused(reply.reason)
                ScanReply.NotPart -> current.value = scanning(NOT_PART)
                is ScanReply.Incomplete -> if (accept(text)) {
                    progress = reply.progress.toFloat()
                    current.value = scanning(READING)
                }
                is ScanReply.Complete -> if (accept(text)) publish(started, decide(started, reply.parsed))
                is ScanReply.Bitcoin -> if (accept(text)) publish(started, decideBitcoin(started, reply.psbt))
                is ScanReply.Solana -> if (accept(text)) publish(started, decideSolana(started, reply.payload))
                is ScanReply.Algorand -> if (accept(text)) publish(started, decideAlgorand(started, reply.payload))
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

    /** SCAN AGAIN after a request matched no wallet, or several. The scan starts from nothing. */
    fun scanAgain() {
        if (current.value !is SignStep.NoMatchingWallet) return
        parts.clear()
        scannedChars = 0L
        progress = null
        current.value = SignStep.Scanning(progress = null, status = SCAN_HINT)
    }

    /**
     * APPROVE on the review. The App Password is asked before anything is signed. A Bitcoin review that asks for a confirmation of its
     * unverified amounts waits until the owner has given it.
     */
    fun approve() {
        when (val step = current.value) {
            is SignStep.Review -> current.value = SignStep.Confirming(step.ready, busy = false)
            is SignStep.BitcoinReview -> if (!step.ready.reviewed.review.requiresConfirmation || step.confirmed) {
                current.value = SignStep.BitcoinConfirming(step.ready, step.confirmed, busy = false)
            }
            is SignStep.SolanaReview -> current.value = SignStep.SolanaConfirming(step.ready, busy = false)
            is SignStep.AlgorandReview -> current.value = SignStep.AlgorandConfirming(step.ready, busy = false)
            else -> Unit
        }
    }

    /** The owner checked, or unchecked, the unverified input amounts of a Bitcoin review. */
    fun confirmUnverified(checked: Boolean) {
        val step = current.value as? SignStep.BitcoinReview ?: return
        current.value = step.copy(confirmed = checked)
    }

    /**
     * The owner typed the App Password. A wrong one leaves the step as it was, with nothing shown. A right one signs the
     * reviewed request. [password] is zeroed before this returns.
     */
    suspend fun confirm(password: CharArray) {
        try {
            gate.withLock {
                val bitcoin = current.value as? SignStep.BitcoinConfirming
                if (bitcoin != null) {
                    confirmBitcoin(bitcoin, password)
                    return
                }
                val solana = current.value as? SignStep.SolanaConfirming
                if (solana != null) {
                    confirmSolana(solana, password)
                    return
                }
                val algorand = current.value as? SignStep.AlgorandConfirming
                if (algorand != null) {
                    confirmAlgorand(algorand, password)
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
            is SignStep.BitcoinConfirming -> cancelBitcoinApproval()
            is SignStep.SolanaConfirming -> cancelSolanaApproval()
            is SignStep.AlgorandConfirming -> cancelAlgorandApproval()
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
        val bitcoin = when (val step = current.value) {
            is SignStep.BitcoinReview -> step.ready
            is SignStep.BitcoinConfirming -> step.ready
            else -> null
        }
        val solana = when (val step = current.value) {
            is SignStep.SolanaReview -> step.ready
            is SignStep.SolanaConfirming -> step.ready
            else -> null
        }
        val algorand = when (val step = current.value) {
            is SignStep.AlgorandReview -> step.ready
            is SignStep.AlgorandConfirming -> step.ready
            else -> null
        }
        epoch++
        current.value = SignStep.Closed
        reviewed?.let {
            recordQuietly(ReviewEntry(it.walletId, ReviewOutcome.CANCELLED, it.request, it, refusal = null))
        }
        bitcoin?.let {
            recordBitcoinQuietly(BitcoinReviewEntry(it.walletId, ReviewOutcome.CANCELLED, psbtBytes(it), it.reviewed, refusal = null))
        }
        solana?.let {
            recordSolanaQuietly(SolanaReviewEntry(it.walletId, ReviewOutcome.CANCELLED, it.request, it.review, refusal = null))
        }
        algorand?.let {
            recordAlgorandQuietly(AlgorandReviewEntry(it.walletId, ReviewOutcome.CANCELLED, it.request, it.review, refusal = null))
        }
    }

    private fun cancelApproval() {
        val waiting = current.value as? SignStep.Confirming ?: return
        if (!waiting.busy) current.value = SignStep.Review(waiting.ready)
    }

    private fun cancelBitcoinApproval() {
        val waiting = current.value as? SignStep.BitcoinConfirming ?: return
        if (!waiting.busy) current.value = SignStep.BitcoinReview(waiting.ready, waiting.confirmed)
    }

    /**
     * Reviews a complete Bitcoin request against the flow's wallet. A flow with no wallet refuses it: a Bitcoin review is checked
     * against one wallet's Bitcoin accounts, so the owner scans from that wallet's sign screen. A refusal is recorded under that wallet.
     */
    private suspend fun decideBitcoin(started: Int, psbt: ByteArray): SignStep {
        val target = walletId ?: return SignStep.Refused(BITCOIN_NEEDS_WALLET)
        return try {
            when (val prepared = ports.bitcoinPrepare(target, psbt)) {
                is BitcoinPrepared.Ready -> SignStep.BitcoinReview(prepared, confirmed = false)
                is BitcoinPrepared.Refused -> bitcoinRefusal(started, target, psbt, prepared.reason, reviewed = null)
            }
        } catch (e: WalletUnavailable) {
            bitcoinRefusal(started, target, psbt, WALLET_UNREADABLE, reviewed = null)
        }
    }

    /**
     * Checks the App Password for a Bitcoin signature, then signs the reviewed request. A refusal is recorded with the review it came
     * after. The caller zeroes [password].
     */
    private suspend fun confirmBitcoin(waiting: SignStep.BitcoinConfirming, password: CharArray) {
        if (waiting.busy) return
        val started = epoch
        current.value = waiting.copy(busy = true)
        val next = when (ports.checkPassword(password)) {
            PasswordCheck.Accepted -> signBitcoin(waiting, started)
            PasswordCheck.Rejected -> SignStep.BitcoinConfirming(waiting.ready, waiting.confirmed, busy = false)
            PasswordCheck.Unavailable ->
                bitcoinRefusal(started, waiting.ready.walletId, psbtBytes(waiting.ready), VAULT_UNCHECKED, waiting.ready.reviewed)
        }
        publish(started, next)
    }

    /**
     * Signs the reviewed Bitcoin request. A refusal, or a wallet that cannot be read, signs nothing and is shown as the reason. The
     * signature is recorded before it is returned, so no signature is shown without its record.
     */
    private suspend fun signBitcoin(waiting: SignStep.BitcoinConfirming, started: Int): SignStep {
        if (started != epoch) return SignStep.Closed
        val ready = waiting.ready
        val parts = try {
            ports.bitcoinSign(ready, waiting.confirmed)
        } catch (e: WalletUnavailable) {
            return bitcoinRefusal(started, ready.walletId, psbtBytes(ready), WALLET_UNREADABLE, ready.reviewed)
        } catch (e: BtcRefusedException) {
            return bitcoinRefusal(started, ready.walletId, psbtBytes(ready), e.refusal.reason, ready.reviewed)
        } catch (e: IllegalArgumentException) {
            return bitcoinRefusal(started, ready.walletId, psbtBytes(ready), SIGN_REFUSED, ready.reviewed)
        }
        return try {
            if (started != epoch) return SignStep.Closed
            ports.recordBitcoin(BitcoinReviewEntry(ready.walletId, ReviewOutcome.SIGNED, psbtBytes(ready), ready.reviewed, refusal = null))
            SignStep.Signature(parts)
        } catch (e: ReviewNotSaved) {
            SignStep.Refused(NOT_SAVED)
        }
    }

    /**
     * The step that shows a refusal of a Bitcoin request, recorded under [wallet] with the [reviewed] review it came after, if any. A
     * refusal decided after the flow was left was never shown, so it is not recorded either.
     */
    private suspend fun bitcoinRefusal(
        started: Int,
        wallet: String,
        psbt: ByteArray,
        reason: String,
        reviewed: BitcoinReviewed?,
    ): SignStep.Refused {
        if (started == epoch) {
            recordBitcoinQuietly(BitcoinReviewEntry(wallet, ReviewOutcome.REFUSED, psbt, reviewed, refusal = reason))
        }
        return SignStep.Refused(reason)
    }

    /** The PSBT of [ready] as the Parser Sandbox wrote it, which is the request the review was made of. */
    private fun psbtBytes(ready: BitcoinPrepared.Ready): ByteArray = Psbt.write(ready.psbt).toByteArray()

    private fun cancelSolanaApproval() {
        val waiting = current.value as? SignStep.SolanaConfirming ?: return
        if (!waiting.busy) current.value = SignStep.SolanaReview(waiting.ready)
    }

    /**
     * Reviews a complete Solana request against the flow's wallet. A flow with no wallet refuses it: a Solana review is checked against one
     * wallet's Solana accounts, so the owner scans from that wallet's sign screen. A refusal is recorded under that wallet.
     */
    private suspend fun decideSolana(started: Int, payload: ByteArray): SignStep {
        val target = walletId ?: return SignStep.Refused(SOLANA_NEEDS_WALLET)
        val request = when (val decoded = SolSignRequest.decode(payload)) {
            is SolSignRequest.Result.Ok -> decoded.request
            is SolSignRequest.Result.Rejected -> return SignStep.Refused(decoded.reason)
        }
        if (request.requestId == null) return solanaRefusal(started, target, request, NO_REQUEST_ID, ready = null)
        return try {
            when (val prepared = ports.solanaPrepare(target, request)) {
                is SolanaPrepared.Ready -> SignStep.SolanaReview(prepared)
                is SolanaPrepared.Refused -> solanaRefusal(started, target, request, prepared.reason, ready = null)
            }
        } catch (e: WalletUnavailable) {
            solanaRefusal(started, target, request, WALLET_UNREADABLE, ready = null)
        }
    }

    /** Checks the App Password for a Solana signature, then signs the reviewed request. The caller zeroes [password]. */
    private suspend fun confirmSolana(waiting: SignStep.SolanaConfirming, password: CharArray) {
        if (waiting.busy) return
        val started = epoch
        current.value = waiting.copy(busy = true)
        val next = when (ports.checkPassword(password)) {
            PasswordCheck.Accepted -> signSolana(waiting, started)
            PasswordCheck.Rejected -> SignStep.SolanaConfirming(waiting.ready, busy = false)
            PasswordCheck.Unavailable ->
                solanaRefusal(started, waiting.ready.walletId, waiting.ready.request, VAULT_UNCHECKED, waiting.ready)
        }
        publish(started, next)
    }

    /**
     * Signs the reviewed Solana request. A refusal, or a wallet that cannot be read, signs nothing and is shown as the reason. The signature
     * is recorded before it is returned, so no signature is shown without its record.
     */
    private suspend fun signSolana(waiting: SignStep.SolanaConfirming, started: Int): SignStep {
        if (started != epoch) return SignStep.Closed
        val ready = waiting.ready
        val parts = try {
            ports.solanaSign(ready)
        } catch (e: WalletUnavailable) {
            return solanaRefusal(started, ready.walletId, ready.request, WALLET_UNREADABLE, ready)
        } catch (e: IllegalArgumentException) {
            return solanaRefusal(started, ready.walletId, ready.request, e.message ?: SIGN_REFUSED, ready)
        }
        return try {
            if (started != epoch) return SignStep.Closed
            ports.recordSolana(SolanaReviewEntry(ready.walletId, ReviewOutcome.SIGNED, ready.request, ready.review, refusal = null))
            SignStep.Signature(parts)
        } catch (e: ReviewNotSaved) {
            SignStep.Refused(NOT_SAVED)
        }
    }

    /**
     * The step that shows a refusal of a Solana request, recorded under [wallet] with the [ready] review it came after, if any. A refusal
     * decided after the flow was left was never shown, so it is not recorded either.
     */
    private suspend fun solanaRefusal(
        started: Int,
        wallet: String,
        request: SolSignRequest,
        reason: String,
        ready: SolanaPrepared.Ready?,
    ): SignStep.Refused {
        if (started == epoch) {
            recordSolanaQuietly(SolanaReviewEntry(wallet, ReviewOutcome.REFUSED, request, ready?.review, refusal = reason))
        }
        return SignStep.Refused(reason)
    }

    /** Writes the Solana [entry]. A write that fails is left out, as the other Review Records are. */
    private suspend fun recordSolanaQuietly(entry: SolanaReviewEntry) {
        try {
            ports.recordSolana(entry)
        } catch (e: ReviewNotSaved) {
            // Left out: the screen already shows the outcome, and nothing is signed for it.
        }
    }

    private fun cancelAlgorandApproval() {
        val waiting = current.value as? SignStep.AlgorandConfirming ?: return
        if (!waiting.busy) current.value = SignStep.AlgorandReview(waiting.ready)
    }

    /**
     * Reviews a complete Algorand request against the flow's wallet. A flow with no wallet refuses it: an Algorand review is checked against
     * one wallet's Algorand accounts, so the owner scans from that wallet's sign screen. A refusal is recorded under that wallet.
     */
    private suspend fun decideAlgorand(started: Int, payload: ByteArray): SignStep {
        val target = walletId ?: return SignStep.Refused(ALGORAND_NEEDS_WALLET)
        val request = when (val decoded = AlgoSignRequest.decode(payload)) {
            is AlgoSignRequest.Result.Ok -> decoded.request
            is AlgoSignRequest.Result.Rejected -> return SignStep.Refused(decoded.reason)
        }
        return try {
            when (val prepared = ports.algorandPrepare(target, request)) {
                is AlgorandPrepared.Ready -> SignStep.AlgorandReview(prepared)
                is AlgorandPrepared.Refused -> algorandRefusal(started, target, request, prepared.reason, ready = null)
            }
        } catch (e: WalletUnavailable) {
            algorandRefusal(started, target, request, WALLET_UNREADABLE, ready = null)
        }
    }

    /** Checks the App Password for an Algorand signature, then signs the reviewed request. The caller zeroes [password]. */
    private suspend fun confirmAlgorand(waiting: SignStep.AlgorandConfirming, password: CharArray) {
        if (waiting.busy) return
        val started = epoch
        current.value = waiting.copy(busy = true)
        val next = when (ports.checkPassword(password)) {
            PasswordCheck.Accepted -> signAlgorand(waiting, started)
            PasswordCheck.Rejected -> SignStep.AlgorandConfirming(waiting.ready, busy = false)
            PasswordCheck.Unavailable ->
                algorandRefusal(started, waiting.ready.walletId, waiting.ready.request, VAULT_UNCHECKED, waiting.ready)
        }
        publish(started, next)
    }

    /**
     * Signs the reviewed Algorand request. A refusal, or a wallet that cannot be read, signs nothing and is shown as the reason. The
     * signature is recorded before it is returned, so no signature is shown without its record.
     */
    private suspend fun signAlgorand(waiting: SignStep.AlgorandConfirming, started: Int): SignStep {
        if (started != epoch) return SignStep.Closed
        val ready = waiting.ready
        val parts = try {
            ports.algorandSign(ready)
        } catch (e: WalletUnavailable) {
            return algorandRefusal(started, ready.walletId, ready.request, WALLET_UNREADABLE, ready)
        } catch (e: IllegalArgumentException) {
            return algorandRefusal(started, ready.walletId, ready.request, e.message ?: SIGN_REFUSED, ready)
        }
        return try {
            if (started != epoch) return SignStep.Closed
            ports.recordAlgorand(AlgorandReviewEntry(ready.walletId, ReviewOutcome.SIGNED, ready.request, ready.review, refusal = null))
            SignStep.Signature(parts)
        } catch (e: ReviewNotSaved) {
            SignStep.Refused(NOT_SAVED)
        }
    }

    /**
     * The step that shows a refusal of an Algorand request, recorded under [wallet] with the [ready] review it came after, if any. A refusal
     * decided after the flow was left was never shown, so it is not recorded either.
     */
    private suspend fun algorandRefusal(
        started: Int,
        wallet: String,
        request: AlgoSignRequest,
        reason: String,
        ready: AlgorandPrepared.Ready?,
    ): SignStep.Refused {
        if (started == epoch) {
            recordAlgorandQuietly(AlgorandReviewEntry(wallet, ReviewOutcome.REFUSED, request, ready?.review, refusal = reason))
        }
        return SignStep.Refused(reason)
    }

    /** Writes the Algorand [entry]. A write that fails is left out, as the other Review Records are. */
    private suspend fun recordAlgorandQuietly(entry: AlgorandReviewEntry) {
        try {
            ports.recordAlgorand(entry)
        } catch (e: ReviewNotSaved) {
            // Left out: the screen already shows the outcome, and nothing is signed for it.
        }
    }

    /** Checks a complete request. Only the first complete request is decided, because scanning stops once it is. */
    private suspend fun decide(started: Int, parsed: ParsedRequest): SignStep {
        val request = parsed.request
        if (request.requestId == null) {
            return refusal(started = started, wallet = walletId, request = request, reason = NO_REQUEST_ID)
        }
        return try {
            val target = walletId ?: when (val match = matchWallet(request, ports.wallets())) {
                is WalletMatch.One -> match.walletId
                WalletMatch.None -> return SignStep.NoMatchingWallet(request, several = false)
                is WalletMatch.Several -> return SignStep.NoMatchingWallet(request, several = true)
            }
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
            return refusal(started = started, wallet = walletId, request = request, reason = WALLET_UNREADABLE)
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

    /** Writes the Bitcoin [entry]. A write that fails is left out, as [recordQuietly] leaves one out. */
    private suspend fun recordBitcoinQuietly(entry: BitcoinReviewEntry) {
        try {
            ports.recordBitcoin(entry)
        } catch (e: ReviewNotSaved) {
            // Left out: the screen already shows the outcome, and nothing is signed for it.
        }
    }

    private fun scanning(status: String): SignStep.Scanning = SignStep.Scanning(progress, status)

    private fun publish(started: Int, step: SignStep) {
        if (started == epoch) current.value = step
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
        const val SIGN_REFUSED = "The request could not be signed."
        const val BITCOIN_NEEDS_WALLET = "Open the wallet this Bitcoin request spends from, then scan it on that wallet's sign screen."
        const val SOLANA_NEEDS_WALLET = "Open the wallet this Solana request is for, then scan it on that wallet's sign screen."
        const val ALGORAND_NEEDS_WALLET = "Open the wallet this Algorand request is for, then scan it on that wallet's sign screen."
    }
}
