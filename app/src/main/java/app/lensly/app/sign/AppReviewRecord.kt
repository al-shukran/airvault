package app.lensly.app.sign

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.ChainId
import app.airvault.core.model.recordFacts
import app.airvault.core.model.Review as CoreReview
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestKind
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.core.wallet.toHex
import java.security.MessageDigest

/**
 * The review of a request of an installed chain app, as the one sign flow holds it: the review the owner reads, the account that signs, and
 * the addresses the review names. The app builds it from the request it parsed.
 */
sealed interface AppPrepared {
    /**
     * The request names an account of the wallet and passed every rule. [request] is the parsed request, which the app signs. [accountIndex] is
     * the account that signs, [signer] its name and address, and [counterparties] the addresses the review names, looked up on [addressChain].
     */
    data class Ready(
        val walletId: String,
        val request: AppRequest,
        val review: CoreReview,
        val accountIndex: Int,
        val signer: SigningAccount,
        val addressChain: ChainId,
        val counterparties: List<String>,
        val addressNotes: Map<String, AddressNotes>,
    ) : AppPrepared

    /** The request cannot be signed. [reason] says why, in plain words. Nothing is signed for it. */
    data class Refused(val reason: String) : AppPrepared
}

/**
 * What the one sign flow showed and decided for one request of an installed chain app, of wallet [walletId]. [reviewed] is the review the owner
 * saw, when the request got one. [acknowledged] are the checks the owner ticked before the signature. [refusal] is the reason, for a refusal.
 */
class AppReviewEntry(
    val walletId: String,
    val outcome: ReviewOutcome,
    val request: AppRequest,
    val reviewed: AppPrepared.Ready?,
    val acknowledged: List<String>,
    val refusal: String?,
)

/**
 * The most rows a Review Record keeps. The history holds at most 256 rows, so a longer review keeps its first rows and a note of how many it
 * left out. Every chain app's record is bounded the same way.
 */
private const val MAX_RECORD_ROWS = 200

/**
 * The Review Record of [entry], written at [atMs] (spec 8.6). Its facts are the review's [recordFacts]: the network, the rows the owner read,
 * and the checks ticked. [origin] is the origin the request names, [dataHash] the hash of its data, and [displayName] the name of its chain app,
 * which names the network of a request refused before any review.
 */
fun appReviewRecordOf(entry: AppReviewEntry, atMs: Long, origin: String?, dataHash: String, displayName: String): ReviewRecord {
    val reviewed = entry.reviewed
    val facts = reviewed?.review?.recordFacts(entry.acknowledged)
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = kindOf(entry.request.header.kind),
        chainId = null,
        networkName = facts?.network?.name ?: displayName,
        accountIndex = reviewed?.accountIndex,
        address = reviewed?.signer?.address,
        function = null,
        to = null,
        amount = null,
        tokenContract = null,
        fee = emptyList(),
        params = keptRows(facts?.rows.orEmpty()),
        origin = origin,
        refusal = entry.refusal,
        dataHash = dataHash,
        acknowledged = entry.acknowledged,
    )
}

/**
 * The origin a request names, and the SHA-256 of the data it asks to sign, as its chain app reads them (spec 8.6). For Algorand that data is the
 * transaction the review decoded. The signature also covers the "TX" tag that go-algorand puts before those bytes. The record does not hash the
 * tag, so its hash is of the transaction the owner was shown.
 */
internal fun appRequestFactsOf(request: AppRequest): Pair<String?, String> = when (request.header.app) {
    algorandManifest.id -> when (val decoded = AlgoSignRequest.decode(request.body)) {
        is AlgoSignRequest.Result.Ok -> decoded.request.origin to sha256Hex(decoded.request.signData)
        is AlgoSignRequest.Result.Rejected -> null to sha256Hex(request.body)
    }
    solanaManifest.id -> when (val decoded = SolSignRequest.decode(request.body)) {
        is SolSignRequest.Result.Ok -> decoded.request.origin to sha256Hex(decoded.request.signData)
        is SolSignRequest.Result.Rejected -> null to sha256Hex(request.body)
    }
    else -> null to sha256Hex(request.body)
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/** The rows a record keeps: all of them up to [MAX_RECORD_ROWS], otherwise the first rows and a note of how many were left out. */
private fun keptRows(rows: List<Pair<String, String>>): List<Pair<String, String>> =
    if (rows.size > MAX_RECORD_ROWS) {
        rows.take(MAX_RECORD_ROWS - 1) + ("More" to "${rows.size - MAX_RECORD_ROWS + 1} rows not kept")
    } else {
        rows
    }

private fun kindOf(kind: RequestKind): ReviewKind = when (kind) {
    RequestKind.TRANSACTION -> ReviewKind.TRANSACTION
    RequestKind.MESSAGE, RequestKind.TYPED_DATA -> ReviewKind.MESSAGE
}
