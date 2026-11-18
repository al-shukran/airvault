package app.lensly.app.sign

import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import app.lensly.app.addressesIn
import app.lensly.core.algorand.AlgoAddress
import app.lensly.core.algorand.AlgorandRequestReview
import app.lensly.core.algorand.AlgorandWarning
import app.lensly.core.algorand.Asa
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

/** The label of an asset transfer's asset row: the asset's name, or "Unknown asset #id" when the catalog does not name it. */
private const val ASSET = "Asset"

/** The label of the asset row, which names the asset by its ID. */
private const val ASSET_ID = "Asset ID"

/** The label of a payment's close-to row: the address that takes the rest of the balance. */
private const val CLOSE_REMAINDER_TO = "Close Remainder To"

/** The label of an asset transfer's close-to row: the address that takes the rest of the holding. */
private const val CLOSE_HOLDING_TO = "Close Holding To"

/** The label of the first round in which the transaction is valid. */
private const val VALID_FROM = "Valid from Round"

/** The label of the last round in which the transaction is valid. A later last round lets the signed transaction be sent later. */
private const val VALID_UNTIL = "Valid until Round"

/** The label of the lease row: until the last round, no other transaction with the same lease can be confirmed. */
private const val LEASE = "Lease"

/** The label of the group ID row: the group the transaction is part of. */
private const val GROUP_ID = "Group ID"

/** The decimal places of a microAlgo: one ALGO is 10^6 microAlgos. */
private const val MICRO_ALGO_DECIMALS = 6

/**
 * The label and value rows an Algorand review shows, in order: the sender, what the transaction moves, its fee, the rounds it is valid
 * in, its lease and group ID when it has them, its note, and its close-to address. The review screen and the Review Record both show
 * these rows, so they say the same thing.
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
            add(ASSET to assetName(txn.assetId, review.asset))
            add(ASSET_ID to txn.assetId.toString())
            // An opt-in moves no units, and its receiver is the sender itself, so it shows neither an amount nor a receiver.
            if (!txn.isOptIn) {
                add(AMOUNT to assetAmount(txn.amount, review.asset))
                add(RECEIVER to AlgoAddress.encode(txn.receiver))
            }
        }
    }
    add(FEE to algoText(txn.fee))
    add(VALID_FROM to txn.firstValid.toString())
    add(VALID_UNTIL to txn.lastValid.toString())
    txn.lease?.let { add(LEASE to "0x" + it.toHex()) }
    txn.group?.let { add(GROUP_ID to "0x" + it.toHex()) }
    noteText(txn.note)?.let { add(NOTE to it) }
    review.closeTo?.let { closeTo ->
        val label = if (txn is Payment) CLOSE_REMAINDER_TO else CLOSE_HOLDING_TO
        add(label to AlgoAddress.encode(closeTo))
    }
}

/**
 * The sentence at the top of the review. A payment names its amount in ALGO and its receiver. An asset transfer names its amount and its
 * asset. An opt-in and an opt-out each say so, and an opt-out names the address that takes the rest of the holding.
 */
/** The addresses an Algorand review shows, each one looked up in the Address Book. */
fun algorandCounterparties(review: AlgorandRequestReview): List<String> = addressesIn(algorandRows(review).map { it.second })

fun algorandSentence(review: AlgorandRequestReview): String = when (val txn = review.txn) {
    is Payment -> "Sends ${algoText(txn.amount)} to ${AlgoAddress.encode(txn.receiver)}."
    is AssetTransfer -> assetSentence(txn, review.asset)
}

/**
 * The sentence for an asset transfer. An opt-in adds the asset to the account. An opt-out removes it and sends what it holds to the
 * close-to address. Any other transfer that also closes the holding says where the rest goes. An asset the catalog does not name is
 * called by its ID, and its amount is in base units.
 */
private fun assetSentence(txn: AssetTransfer, asset: Asa?): String {
    val name = asset?.unitName ?: "asset ${txn.assetId}"
    val amount = if (asset == null) "${txn.amount} base units of asset ${txn.assetId}" else assetAmount(txn.amount, asset)
    val receiver = AlgoAddress.encode(txn.receiver)
    val closeTo = txn.closeTo?.let { AlgoAddress.encode(it) }
    return when {
        txn.isOptIn -> "Opt in to $name."
        txn.isOptOut && closeTo != null -> "Opt out of $name, remaining balance goes to $closeTo."
        closeTo != null -> "Sends $amount to $receiver. The rest goes to $closeTo, and the asset is removed."
        else -> "Sends $amount to $receiver."
    }
}

/** The title of the Algorand review. An asset opt-in is titled as one, so that it does not read as a transfer of zero units. */
fun algorandTitle(review: AlgorandRequestReview): String = if ((review.txn as? AssetTransfer)?.isOptIn == true) "Opt In to Asset" else "Review"

/** The short facts under the sentence: the fee, in ALGO. */
fun algorandFacts(review: AlgorandRequestReview): List<Pair<String, String>> = listOf(FEE to algoText(review.txn.fee))

/** The text of one warning the review shows. A close-to warning says what leaves the account and where it goes. */
fun algorandWarningText(review: AlgorandRequestReview, warning: AlgorandWarning): String = when (warning) {
    AlgorandWarning.CLOSE_TO -> when (review.txn) {
        is Payment ->
            "This closes the account. All of its remaining ALGO goes to the close-to address below, and the account is removed."
        is AssetTransfer ->
            "This sends all of the remaining units of this asset to the close-to address below and removes the asset from the account."
    }
    AlgorandWarning.UNKNOWN_ASSET -> when (val txn = review.txn) {
        is AssetTransfer ->
            "Unknown asset #${txn.assetId}: Lensly does not know its name or decimals, so its amount is shown in base units."
        is Payment -> "Lensly does not know this asset."
    }
    AlgorandWarning.GROUP -> "Part of a group: other transactions in the group are not shown"
}

/** The name of an asset in a row: its unit name, with its name in brackets when the two differ, or "Unknown asset #id". */
private fun assetName(assetId: ULong, asset: Asa?): String = when {
    asset == null -> "Unknown asset #$assetId"
    asset.name.isEmpty() || asset.name == asset.unitName -> asset.unitName
    else -> "${asset.unitName} (${asset.name})"
}

/** An asset amount in its unit, with exactly the asset's decimal places, when the catalog gives them. Otherwise in base units. */
private fun assetAmount(amount: ULong, asset: Asa?): String =
    if (asset == null) "$amount base units" else "${unitsText(amount, asset.decimals)} ${asset.unitName}"

/** A base-unit count written with [decimals] decimal places, as ALGO is written with six. */
private fun unitsText(baseUnits: ULong, decimals: Int): String =
    BigDecimal(baseUnits.toString()).movePointLeft(decimals).setScale(decimals).toPlainString()

/** A microAlgo count as ALGO, with exactly six decimal places. */
private fun algoText(microAlgos: ULong): String =
    BigDecimal(microAlgos.toString()).movePointLeft(MICRO_ALGO_DECIMALS).setScale(MICRO_ALGO_DECIMALS).toPlainString() + " ALGO"

/**
 * The note as text when every character can be read, as the Solana messages are. Any other note shows its bytes as 0x hex. Null for an
 * empty note, which a row leaves out.
 */
private fun noteText(note: ByteArray): String? =
    if (note.isEmpty()) null else printableText(note) ?: ("0x" + note.toHex())
