package app.lensly.app.sign

import app.airvault.core.model.ChainId
import app.airvault.core.model.recordFacts
import app.airvault.core.model.Review as CoreReview
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestKind
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount

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
        params = facts?.rows.orEmpty(),
        origin = origin,
        refusal = entry.refusal,
        dataHash = dataHash,
        acknowledged = entry.acknowledged,
    )
}

private fun kindOf(kind: RequestKind): ReviewKind = when (kind) {
    RequestKind.TRANSACTION -> ReviewKind.TRANSACTION
    RequestKind.MESSAGE, RequestKind.TYPED_DATA -> ReviewKind.MESSAGE
}
