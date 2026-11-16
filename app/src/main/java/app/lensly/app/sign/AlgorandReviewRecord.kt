package app.lensly.app.sign

import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.ur.AlgoSignRequest
import app.lensly.core.ur.AlgoSignType
import app.lensly.core.wallet.toHex
import java.security.MessageDigest

/** The network a record names when the request was refused before a review named one. */
private const val ALGORAND_NETWORK = "Algorand"

/**
 * What the Algorand sign flow showed and decided for one request, of wallet [walletId]. [reviewed] is the review the owner saw, when the
 * request got one. [refusal] is the reason, for a refusal.
 */
class AlgorandReviewEntry(
    val walletId: String,
    val outcome: ReviewOutcome,
    val request: AlgoSignRequest,
    val reviewed: AlgorandRequestReview?,
    val refusal: String?,
)

/**
 * The Review Record of the Algorand request [entry], written at [atMs]. The record keeps the signing account and its address, the rows the
 * review showed, and the SHA-256 of the signed bytes. It holds no key material.
 */
fun algorandReviewRecordOf(entry: AlgorandReviewEntry, atMs: Long): ReviewRecord {
    val reviewed = entry.reviewed
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = if (entry.request.signType == AlgoSignType.MESSAGE) ReviewKind.MESSAGE else ReviewKind.TRANSACTION,
        chainId = null,
        networkName = reviewed?.network?.displayName ?: ALGORAND_NETWORK,
        accountIndex = reviewed?.accountIndex,
        address = reviewed?.signerAddress,
        function = null,
        to = null,
        amount = null,
        tokenContract = null,
        fee = emptyList(),
        params = reviewed?.let(::algorandRows).orEmpty(),
        origin = entry.request.origin,
        refusal = entry.refusal,
        dataHash = MessageDigest.getInstance("SHA-256").digest(entry.request.signData).toHex(),
    )
}
