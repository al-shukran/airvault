package app.lensly.app.sign

import app.airvault.chains.bitcoin.signer.btcText
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.text
import app.lensly.app.BitcoinReviewed
import app.lensly.core.registry.Registry
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.bitcoinNetworkName
import app.lensly.ui.sign.bitcoinReviewOf
import app.lensly.ui.sign.scriptTypeName
import app.lensly.ui.sign.unknownNetworkName
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
    MESSAGE(1),

    /** A Bitcoin PSBT. Its facts are in [ReviewRecord.bitcoin]. */
    BITCOIN_TRANSACTION(2);

    companion object {
        /** The kind stored as [wire] in a record. An unknown value is refused. */
        fun fromWire(wire: Int): ReviewKind = entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("Unknown review kind.")
    }
}

/** Whether a notice of a Bitcoin review only warns, or blocks the request. */
enum class NoticeLevel(val wire: Int) {
    /** A warning the owner read on the review. Signing was still allowed. */
    WARN(0),

    /** The reason the request was refused, after the review or before it. Nothing was signed. */
    BLOCK(1);

    companion object {
        /** The level stored as [wire] in a record. An unknown value is refused. */
        fun fromWire(wire: Int): NoticeLevel = entries.firstOrNull { it.wire == wire }
            ?: throw IllegalArgumentException("Unknown notice level.")
    }
}

/** One notice a Bitcoin review showed, with its words as shown. */
data class BitcoinRecordNotice(val level: NoticeLevel, val text: String)

/** One output of a Bitcoin request, as the review showed it. [address] is null for a script with no address. */
data class BitcoinRecordOutput(val address: String?, val amountSats: Long, val isOwnChange: Boolean)

/**
 * What a Bitcoin review showed of its PSBT. A record of any other request has none. [scriptType] is the Script Type of the account, as
 * shown. The fee is null while an input amount is unverified, and so is its rate.
 */
data class BitcoinRecordFacts(
    val scriptType: String,
    val outputs: List<BitcoinRecordOutput>,
    val feeSats: Long?,
    val feeRateSatPerVb: Double?,
    val notices: List<BitcoinRecordNotice>,
)

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
    /** What a Bitcoin review showed of the PSBT. Null for every other record. */
    val bitcoin: BitcoinRecordFacts? = null,
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
 * What the Bitcoin sign flow showed and decided for one PSBT of wallet [walletId]. [reviewed] is the review the owner saw, when the
 * request got one. [refusal] is the reason, for a refusal. [psbt] is the PSBT as the Parser Sandbox wrote it, and its SHA-256 is kept.
 */
class BitcoinReviewEntry(
    val walletId: String,
    val outcome: ReviewOutcome,
    val psbt: ByteArray,
    val reviewed: BitcoinReviewed?,
    val refusal: String?,
)

/**
 * The Review Record of the Bitcoin request [entry], written at [atMs]. The record keeps the account, its Script Type and network, each
 * output, the fee and its rate, and every notice the review showed. A refusal after the review adds its reason as a BLOCK notice.
 */
fun bitcoinReviewRecordOf(entry: BitcoinReviewEntry, atMs: Long): ReviewRecord {
    val reviewed = entry.reviewed
    val review = reviewed?.review
    val reviewModel = reviewed?.let { bitcoinReviewOf(it.review, it.account.network, it.account.scriptType) }
    val notices = buildList {
        reviewModel?.warnings?.forEach { add(BitcoinRecordNotice(NoticeLevel.WARN, it.message)) }
        if (entry.outcome == ReviewOutcome.REFUSED && review != null) {
            entry.refusal?.let { add(BitcoinRecordNotice(NoticeLevel.BLOCK, it)) }
        }
    }
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = ReviewKind.BITCOIN_TRANSACTION,
        chainId = null,
        networkName = reviewed?.account?.network?.let(::bitcoinNetworkName),
        accountIndex = reviewed?.account?.index,
        address = null,
        function = null,
        to = null,
        amount = review?.let { btcText(it.outputs.filterNot { output -> output.isOwnChange }.sumOf { output -> output.amountSats }) },
        tokenContract = null,
        fee = emptyList(),
        params = emptyList(),
        origin = null,
        refusal = entry.refusal,
        dataHash = bitcoinDataHash(entry.psbt),
        bitcoin = reviewed?.let { shown ->
            BitcoinRecordFacts(
                scriptType = scriptTypeName(shown.account.scriptType),
                outputs = shown.review.outputs.map { BitcoinRecordOutput(it.address, it.amountSats, it.isOwnChange) },
                feeSats = shown.review.feeSats,
                feeRateSatPerVb = shown.review.feeRateSatPerVb,
                notices = notices,
            )
        },
    )
}

/** The SHA-256 of the PSBT bytes [psbt], in hex. */
fun bitcoinDataHash(psbt: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(psbt).toHex()

/**
 * The Review Record of [entry], written at [atMs]. A review gives the facts the owner saw: its title is the function, its header names the
 * target, its summary states the amount, and its facts and argument rows are the fee and the params. Without a review, the chain and its
 * network name come from the request and [registry].
 */
fun reviewRecordOf(entry: ReviewEntry, atMs: Long, registry: Registry): ReviewRecord {
    val request = entry.request
    val ready = entry.ready
    val review = ready?.review
    val chainId = request.chainId
    // The first section is the header, the rows that name the request's target and function.
    val header = review?.sections?.firstOrNull()?.rows.orEmpty()
    val amount = review?.summary?.filterIsInstance<SummaryPart.Mono>()?.firstOrNull()?.text
    val target = header.firstOrNull { it.label == "To" || it.label == "Contract" }
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = kindOf(request.dataType),
        chainId = chainId,
        networkName = chainId?.let { registry.network(it)?.name ?: unknownNetworkName(it) },
        accountIndex = ready?.accountIndex,
        address = ready?.signer?.address,
        function = review?.title,
        to = target?.value?.text()?.takeIf { it.isNotEmpty() },
        amount = amount,
        // A token amount names the contract that moves it, which is the review's contract row.
        tokenContract = target?.takeIf { it.label == "Contract" && amount != null }?.value?.text(),
        fee = review?.facts.orEmpty().map { it.label to it.value.text() },
        params = review?.sections.orEmpty().drop(1).flatMap { it.rows }.filter { it.value != null }
            .map { it.label to it.value.text() },
        origin = request.origin,
        refusal = entry.refusal,
        dataHash = MessageDigest.getInstance("SHA-256").digest(request.signData).toHex(),
    )
}

private fun kindOf(type: DataType): ReviewKind = when (type) {
    DataType.TRANSACTION, DataType.TYPED_TRANSACTION -> ReviewKind.TRANSACTION
    DataType.PERSONAL_MESSAGE, DataType.TYPED_DATA -> ReviewKind.MESSAGE
}
