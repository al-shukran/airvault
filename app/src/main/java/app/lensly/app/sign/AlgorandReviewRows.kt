package app.lensly.app.sign

import app.lensly.core.algorand.AlgoAddress
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.algorand.AlgorandWarning
import app.lensly.core.algorand.AssetTransfer
import app.lensly.core.algorand.Payment
import app.lensly.core.wallet.toHex
import java.math.BigDecimal

/** The label of the sending account's row. */
private const val SENDER = "Sender"

/** The label of the receiving account's row. */
private const val RECEIVER = "Receiver"

/** The label of the amount row. */
private const val AMOUNT = "Amount"

/** The label of the fee row. */
private const val FEE = "Fee"

/** The label of the note row. */
private const val NOTE = "Note"

/** The label of the asset row, which names the asset by its ID. */
private const val ASSET_ID = "Asset ID"

/** The label of a payment's close-to row: the address that takes the rest of the balance. */
private const val CLOSE_REMAINDER_TO = "Close remainder to"

/** The label of an asset transfer's close-to row: the address that takes the rest of the holding. */
private const val CLOSE_HOLDING_TO = "Close holding to"

/** The decimal places of a microAlgo: one ALGO is 10^6 microAlgos. */
private const val MICRO_ALGO_DECIMALS = 6

/**
 * The label and value rows an Algorand review shows, in order: the sender, what the transaction moves, its fee, its note, and its close-to
 * address. The review screen and the Review Record both show these rows, so they say the same thing.
 */
fun algorandRows(review: AlgorandRequestReview): List<Pair<String, String>> = buildList {
    val txn = review.txn
    add(SENDER to review.signerAddress)
    when (txn) {
        is Payment -> {
            add(RECEIVER to AlgoAddress.encode(txn.receiver))
            add(AMOUNT to algoText(txn.amount))
        }
        is AssetTransfer -> {
            add(ASSET_ID to txn.assetId.toString())
            add(AMOUNT to "${txn.amount} raw units")
            add(RECEIVER to AlgoAddress.encode(txn.receiver))
        }
    }
    add(FEE to algoText(txn.fee))
    noteText(txn.note)?.let { add(NOTE to it) }
    review.closeTo?.let { closeTo ->
        val label = if (txn is Payment) CLOSE_REMAINDER_TO else CLOSE_HOLDING_TO
        add(label to AlgoAddress.encode(closeTo))
    }
}

/**
 * The sentence at the top of the review. A payment names its amount in ALGO and its receiver. An opt-in says so. Any other asset transfer
 * names its amount in raw units, its asset and its receiver.
 */
fun algorandSentence(review: AlgorandRequestReview): String = when (val txn = review.txn) {
    is Payment -> "Sends ${algoText(txn.amount)} to ${AlgoAddress.encode(txn.receiver)}."
    is AssetTransfer ->
        if (isOptIn(txn)) {
            "Opts in to asset ${txn.assetId}."
        } else {
            "Sends ${txn.amount} raw units of asset ${txn.assetId} to ${AlgoAddress.encode(txn.receiver)}."
        }
}

/** The short facts under the sentence: the fee, in ALGO. */
fun algorandFacts(review: AlgorandRequestReview): List<Pair<String, String>> = listOf(FEE to algoText(review.txn.fee))

/** The text of one warning the review shows. A close-to warning says what leaves the account and where it goes. */
fun algorandWarningText(review: AlgorandRequestReview, warning: AlgorandWarning): String = when (warning) {
    AlgorandWarning.CLOSE_TO -> when (review.txn) {
        is Payment ->
            "This closes the account. All of its remaining ALGO goes to the close-to address below, and the account is removed."
        is AssetTransfer ->
            "This removes the asset from the account. All of its remaining units go to the close-to address below."
    }
}

/** An opt-in: an asset transfer of zero units to the sender's own address. */
private fun isOptIn(txn: AssetTransfer): Boolean = txn.amount == 0UL && txn.receiver.contentEquals(txn.sender)

/** A microAlgo count as ALGO, with exactly six decimal places. */
private fun algoText(microAlgos: ULong): String =
    BigDecimal(microAlgos.toString()).movePointLeft(MICRO_ALGO_DECIMALS).setScale(MICRO_ALGO_DECIMALS).toPlainString() + " ALGO"

/**
 * The note as text when every character can be read, as the Solana messages are. Any other note shows its bytes as 0x hex. Null for an
 * empty note, which a row leaves out.
 */
private fun noteText(note: ByteArray): String? =
    if (note.isEmpty()) null else printableText(note) ?: ("0x" + note.toHex())
