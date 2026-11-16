package app.lensly.app.sign

import app.lensly.core.solana.SolanaRequestReview
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import app.lensly.core.wallet.toHex
import java.security.MessageDigest

/** The network a Solana record names. A sol-sign-request names no cluster, so the record names none either. */
const val SOLANA_NETWORK = "Solana"

/**
 * The most rows a Solana Review Record keeps. The history holds at most 256 rows, so a longer review keeps its first rows and a note of
 * how many it left out.
 */
private const val MAX_RECORD_ROWS = 200

/**
 * What the Solana sign flow showed and decided for one request, of wallet [walletId]. [reviewed] is the review the owner saw, when the
 * request got one. [refusal] is the reason, for a refusal.
 */
class SolanaReviewEntry(
    val walletId: String,
    val outcome: ReviewOutcome,
    val request: SolSignRequest,
    val reviewed: SolanaRequestReview?,
    val refusal: String?,
)

/**
 * The Review Record of the Solana request [entry], written at [atMs]. A transaction is a transaction record and a message is a message
 * record. The record keeps the signing account and its address, the rows the review showed, and the SHA-256 of the signed bytes. It
 * holds no key material.
 */
fun solanaReviewRecordOf(entry: SolanaReviewEntry, atMs: Long): ReviewRecord {
    val reviewed = entry.reviewed
    val rows = reviewed?.let(::solanaRows).orEmpty()
    val kept = if (rows.size > MAX_RECORD_ROWS) {
        rows.take(MAX_RECORD_ROWS - 1) + ("More" to "${rows.size - MAX_RECORD_ROWS + 1} rows not kept")
    } else {
        rows
    }
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = if (entry.request.signType == SolSignType.MESSAGE) ReviewKind.MESSAGE else ReviewKind.TRANSACTION,
        chainId = null,
        networkName = SOLANA_NETWORK,
        accountIndex = reviewed?.accountIndex,
        address = reviewed?.signerAddress,
        function = null,
        to = null,
        amount = null,
        tokenContract = null,
        fee = emptyList(),
        params = kept,
        origin = entry.request.origin,
        refusal = entry.refusal,
        dataHash = MessageDigest.getInstance("SHA-256").digest(entry.request.signData).toHex(),
    )
}
