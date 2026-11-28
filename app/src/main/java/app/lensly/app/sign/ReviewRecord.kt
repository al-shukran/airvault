package app.lensly.app.sign

import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.btcText
import app.airvault.core.model.Danger
import app.airvault.core.model.Review
import app.lensly.app.BitcoinReviewed
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.scriptTypeName
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

/** The most outputs a Bitcoin record keeps. A PSBT with more keeps its first outputs and counts the rest in [BitcoinRecordFacts.outputsNotKept]. */
const val MAX_RECORD_OUTPUTS = 200

/**
 * What a Bitcoin review showed of its PSBT. A record of any other request has none. [scriptType] is the Script Type of the account, as
 * shown. [accountAddress] is that account's first receive address, which names the account the change went to without the seed. The fee is null while an input amount is unverified, and so is its rate. [outputsNotKept] counts the outputs past
 * [MAX_RECORD_OUTPUTS] that [outputs] leaves out.
 */
data class BitcoinRecordFacts(
    val scriptType: String,
    val accountAddress: String,
    val outputs: List<BitcoinRecordOutput>,
    val feeSats: Long?,
    val feeRateSatPerVb: Double?,
    val notices: List<BitcoinRecordNotice>,
    val outputsNotKept: Int = 0,
)

/**
 * The device's own record of one review: what the owner was shown, what was decided, and when. It holds no key material. The
 * record is sealed under the wallet's Per-Wallet Key, and [dataHash] is the SHA-256 of the reviewed data.
 *
 * The facts are the ones the owner saw. A refusal before any review keeps what the request named: its network, its origin and the
 * reason. Every address is kept in full, and so is the text of a message or the decoded arguments of a call, in [params]. [fee]
 * and [params] are label and value rows, in the order they were shown.
 */
data class ReviewRecord(
    val atMs: Long,
    val outcome: ReviewOutcome,
    val kind: ReviewKind,
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
    /** The checks the owner ticked before the signature, by label. Empty for every record that was not signed. */
    val acknowledged: List<String> = emptyList(),
    /**
     * The warnings the review showed, by level and message, whether or not the request was signed. A Bitcoin record keeps its notices
     * instead.
     */
    val warnings: List<Pair<Danger, String>> = emptyList(),
)

/**
 * What the Bitcoin review [reviewed] showed of its PSBT, as the record keeps it: the Script Type, each output, the fee and its rate, and a WARN
 * notice for each warning of [review], the review the owner read.
 */
fun bitcoinRecordFactsOf(reviewed: BitcoinReviewed, review: Review): BitcoinRecordFacts {
    val outputs = reviewed.review.outputs
    return BitcoinRecordFacts(
        scriptType = scriptTypeName(reviewed.account.scriptType),
        accountAddress = reviewed.account.firstReceiveAddress,
        outputs = outputs.take(MAX_RECORD_OUTPUTS).map { BitcoinRecordOutput(it.address, it.amountSats, it.isOwnChange) },
        feeSats = reviewed.review.feeSats,
        feeRateSatPerVb = reviewed.review.feeRateSatPerVb,
        notices = review.warnings.map { BitcoinRecordNotice(NoticeLevel.WARN, it.message) },
        outputsNotKept = (outputs.size - MAX_RECORD_OUTPUTS).coerceAtLeast(0),
    )
}

/**
 * The Review Record of the Bitcoin request [entry], written at [atMs]. The record keeps the account, its Script Type and network, each output, the
 * fee and its rate, and every notice the review showed. A refusal after the review adds its reason as a BLOCK notice. A refusal before any review
 * keeps only its reason, since no account was chosen.
 */
fun bitcoinReviewRecordOf(entry: AppReviewEntry, atMs: Long): ReviewRecord {
    val reviewed = entry.reviewed
    val facts = reviewed?.bitcoin
    val block = entry.refusal?.takeIf { entry.outcome == ReviewOutcome.REFUSED && reviewed != null }
    return ReviewRecord(
        atMs = atMs,
        outcome = entry.outcome,
        kind = ReviewKind.BITCOIN_TRANSACTION,
        networkName = reviewed?.review?.network?.name,
        accountIndex = reviewed?.accountIndex,
        address = null,
        function = null,
        to = null,
        amount = facts?.let { btcText(it.outputs.filterNot { output -> output.isOwnChange }.sumOf { output -> output.amountSats }) },
        tokenContract = null,
        fee = emptyList(),
        params = emptyList(),
        origin = null,
        refusal = entry.refusal,
        dataHash = bitcoinDataHash(BtcPsbt.requestPsbt(entry.request.body)),
        bitcoin = facts?.let { shown -> shown.copy(notices = shown.notices + listOfNotNull(block?.let { BitcoinRecordNotice(NoticeLevel.BLOCK, it) })) },
        // The checks the owner ticked: on a signed request, the approval's checks, which are the review's checkLabels.
        acknowledged = entry.acknowledged,
    )
}

/** The SHA-256 of the PSBT bytes [psbt], in hex. */
fun bitcoinDataHash(psbt: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(psbt).toHex()
