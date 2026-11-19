package app.lensly.app.sign

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.AlgorandNetwork
import app.airvault.chains.algorand.signer.AlgorandRequestReview
import app.airvault.chains.algorand.signer.toReview
import app.airvault.core.model.recordFacts
import app.lensly.core.wallet.toHex
import java.security.MessageDigest

/** The network a record names when the request was refused before a review named one. */
internal const val ALGORAND_NETWORK = "Algorand"

/** Every network name an Algorand record can carry: the name of each network Lensly signs for, or [ALGORAND_NETWORK]. */
internal val ALGORAND_NETWORK_NAMES: Set<String> = AlgorandNetwork.entries.map { it.displayName }.toSet() + ALGORAND_NETWORK

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
        params = reviewed?.toReview()?.recordFacts()?.rows.orEmpty(),
        origin = entry.request.origin,
        refusal = entry.refusal,
        dataHash = MessageDigest.getInstance("SHA-256").digest(entry.request.signData).toHex(),
    )
}
