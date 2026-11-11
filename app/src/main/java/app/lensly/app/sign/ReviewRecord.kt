package app.lensly.app.sign

import app.lensly.core.registry.Registry
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.UNKNOWN_CHAIN_NAME
import java.security.MessageDigest

/** What a Review Record says was decided. */
enum class ReviewOutcome(val wire: Int) {
    /** A signature was made, and it was handed back to the online wallet. */
    SIGNED(0),

    /** Lensly refused the request, and the owner was shown why. */
    REFUSED(1),

    /** The owner left a reviewed request without signing it. */
    CANCELLED(2);

    companion object {
        /** The outcome stored as [wire] in a record. An unknown value is refused. */
        fun fromWire(wire: Int): ReviewOutcome = entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("Unknown review outcome.")
    }
}

/** What was reviewed. Typed data is a message too: it is signed as one, never sent as a transaction. */
enum class ReviewKind(val wire: Int) {
    TRANSACTION(0),
    MESSAGE(1);

    companion object {
        /** The kind stored as [wire] in a record. An unknown value is refused. */
        fun fromWire(wire: Int): ReviewKind = entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("Unknown review kind.")
    }
}

/**
 * The device's own record of one review: what the owner was shown, what was decided, and when. It holds no key material. The
 * reviewed data is kept only as [dataHash], never in full.
 *
 * The facts are the ones the owner saw. A refusal before any review keeps what the request named: its chain, its origin and the
 * reason. Every address is kept in full. [fee] and [params] are label and value rows, in the order they were shown.
 */
data class ReviewRecord(
    val atMs: Long,
    val outcome: ReviewOutcome,
    val kind: ReviewKind,
    /** The chain the request named, or null when it named none. */
    val chainId: Long?,
    /** The network's name, or the unknown-chain name for a chain outside the registry. Null when the request named no chain. */
    val networkName: String?,
    /** The index of the account that signs, or null when no account was chosen. */
    val accountIndex: Int?,
    /** The full address of that account, or null when no account was chosen. */
    val address: String?,
    /** The function the review showed, such as transfer. Null before a review. */
    val function: String?,
    /** The recipient or spender, in full. Null when there is none. */
    val to: String?,
    /** The amount with its unit, as shown, such as "0.0100 MON". Null when there is none. */
    val amount: String?,
    /** The contract of the token that moves, in full. Null for a native transfer and for a message. */
    val tokenContract: String?,
    val fee: List<Pair<String, String>>,
    /** The decoded arguments of a call, or the text of a message. */
    val params: List<Pair<String, String>>,
    val origin: String?,
    /** The reason the request was refused. Null when it was not refused. */
    val refusal: String?,
    /** The SHA-256 of the reviewed data, in hex. */
    val dataHash: String,
)

/**
 * What the sign flow showed and decided for one request, of wallet [walletId]. [ready] is the review the owner saw, when the
 * request got one. [refusal] is the reason, for a refusal.
 */
class ReviewEntry(
    val walletId: String,
    val outcome: ReviewOutcome,
    val request: EthSignRequest,
    val ready: Prepared.Ready?,
    val refusal: String?,
)

/**
 * The Review Record of [entry], written at [atMs]. A review gives the facts the owner saw. Without one, the chain and its network
 * name come from the request and [registry].
 */
fun reviewRecordOf(entry: ReviewEntry, atMs: Long, registry: Registry): ReviewRecord {
    val request = entry.request
    val review = entry.ready?.review
    val chainId = review?.chainId ?: request.chainId
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = kindOf(request.dataType),
        chainId = chainId,
        networkName = review?.networkName ?: chainId?.let { registry.network(it)?.name ?: UNKNOWN_CHAIN_NAME },
        accountIndex = entry.ready?.accountIndex,
        address = review?.fromAddress,
        function = review?.function,
        to = review?.toAddress?.takeIf { it.isNotEmpty() },
        amount = review?.let { listOf(it.amount, it.symbol).filter { part -> part.isNotEmpty() }.joinToString(" ") }
            ?.takeIf { it.isNotEmpty() },
        tokenContract = review?.tokenContract,
        fee = review?.let { it.fee + listOfNotNull(it.feeNote?.let { note -> "Note" to note }) }.orEmpty(),
        params = review?.params.orEmpty(),
        origin = request.origin,
        refusal = entry.refusal,
        dataHash = MessageDigest.getInstance("SHA-256").digest(request.signData).toHex(),
    )
}

private fun kindOf(type: DataType): ReviewKind = when (type) {
    DataType.TRANSACTION, DataType.TYPED_TRANSACTION -> ReviewKind.TRANSACTION
    DataType.PERSONAL_MESSAGE, DataType.TYPED_DATA -> ReviewKind.MESSAGE
}
