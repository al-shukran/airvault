package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.Warning
import app.airvault.core.model.checked
import app.airvault.core.model.printableText
import app.airvault.core.model.text
import app.airvault.core.model.trimmedAmount
import app.airvault.chains.algorand.AlgoAddress
import java.math.BigInteger
import java.util.Base64

/** The characters of the base64 genesis hash that name an Algorand network in a CAIP-2 ID (the manifest's two chain IDs have this form). */
private const val GENESIS_REFERENCE_CHARS = 32

/** The decimal places of a microAlgo: one ALGO is 10^6 microAlgos. */
private const val MICRO_ALGO_DECIMALS = 6

/** The label the owner ticks before a close-to payment or transfer, which sends the rest of the balance away. Sentence case, beside its checkbox. */
private const val ACK_CLOSE_TO = "I understand this sends the remaining balance to the close-to address."

/** The note under the origin the wallet names: the origin is the wallet's word, so Lensly cannot check it. Same as the Solana review. */
private const val ORIGIN_NOTE = "Named by the wallet. Lensly cannot verify it."

/** The label of the origin a request names. The request sets it, so the review does not vouch for it. Same as the EVM and Solana reviews. */
private const val CLAIMED_ORIGIN_LABEL = "Claimed by Request (Not Verified)"

/**
 * The CAIP-2 chain ID of the network whose genesis hash is [genesisHash]: `algorand:` and the first 32 characters of the URL-safe base64
 * hash. The CAIP-2 reference alphabet has no `+` or `/`, which standard base64 uses.
 */
fun algorandChainId(genesisHash: ByteArray): ChainId =
    ChainId("algorand:" + Base64.getUrlEncoder().withoutPadding().encodeToString(genesisHash).take(GENESIS_REFERENCE_CHARS))

/** The decoded fields of an Algorand transaction, as the review names them. */
private object Fields {
    val type = FieldPath("algorand.type")
    val sender = FieldPath("algorand.sender")
    val receiver = FieldPath("algorand.receiver")
    val amount = FieldPath("algorand.amount")
    val assetId = FieldPath("algorand.assetId")
    val fee = FieldPath("algorand.fee")
    val firstValid = FieldPath("algorand.firstValid")
    val lastValid = FieldPath("algorand.lastValid")
    val genesisId = FieldPath("algorand.genesisId")
    val genesisHash = FieldPath("algorand.genesisHash")
    val note = FieldPath("algorand.note")
    val lease = FieldPath("algorand.lease")
    val group = FieldPath("algorand.group")
    val closeTo = FieldPath("algorand.closeTo")
    val rekeyTo = FieldPath("algorand.rekeyTo")
    val assetSender = FieldPath("algorand.assetSender")
    val origin = FieldPath("algorand.origin")
}

/**
 * The review of this request (spec 3.2), with the coverage check applied: a field the parser read that no row shows is a BLOCK. The title,
 * the sentence, the rows and the warnings are the ones the Algorand review has always shown.
 */
fun AlgorandRequestReview.toReview(): Review {
    val chain = algorandChainId(network.genesisHash)
    return Review(
        title = if ((txn as? AssetTransfer)?.isOptIn == true) "Opt In to Asset" else "Review",
        chain = chain,
        network = NetworkLabel(name = network.displayName, testnet = network.testnet, detail = "Genesis ID ${network.genesisId}"),
        summary = listOf(sentence()),
        // The fee is a row of the Transaction section. The Review Record keeps the rows, so the fee is recorded once, there.
        facts = emptyList(),
        sections = listOf(ReviewSection("Transaction", rows(chain))),
        warnings = warnings.map { warningOf(it) },
        coverage = Coverage(decoded = decodedFields(), excluded = excludedFields()),
    ).checked()
}

/** The text of one warning the review shows. A close-to warning says what leaves the account and where it goes. */
fun AlgorandRequestReview.warningText(warning: AlgorandWarning): String = when (warning) {
    AlgorandWarning.CLOSE_TO -> when (txn) {
        is Payment ->
            "This closes the account. All of its remaining ALGO goes to the close-to address below, and the account is removed."
        is AssetTransfer ->
            "This sends all of the remaining units of this asset to the close-to address below and removes the asset from the account."
    }
    AlgorandWarning.UNKNOWN_ASSET -> when (val t = txn) {
        is AssetTransfer ->
            "Unknown asset #${t.assetId}: Lensly does not know its name or decimals, so its amount is shown in base units."
        is Payment -> "Lensly does not know this asset."
    }
    AlgorandWarning.GROUP -> "Part of a group: other transactions in the group are not shown"
    AlgorandWarning.LEASE -> "This transaction carries a lease: another transaction with the same lease cannot be confirmed until round ${txn.lastValid}."
}

private fun AlgorandRequestReview.warningOf(warning: AlgorandWarning): Warning = when (warning) {
    AlgorandWarning.CLOSE_TO -> Warning(Danger.DANGER, "algorand.close-to", warningText(warning), Fields.closeTo, acknowledge = ACK_CLOSE_TO)
    AlgorandWarning.UNKNOWN_ASSET -> Warning(Danger.CAUTION, "algorand.unknown-asset", warningText(warning), Fields.assetId)
    AlgorandWarning.GROUP -> Warning(Danger.CAUTION, "algorand.group", warningText(warning), Fields.group)
    AlgorandWarning.LEASE -> Warning(Danger.CAUTION, "algorand.lease", warningText(warning), Fields.lease)
}

/**
 * The summary at the top of the review. A payment or an asset transfer to one receiver is a [SummaryPart.Send] of its amount, from the
 * signing account, or from the holding a clawback takes. One that also closes the account or holding, and an opt-in or an opt-out, keep
 * their sentence, since the rest of the balance goes elsewhere.
 */
private fun AlgorandRequestReview.sentence(): SummaryPart = when (val t = txn) {
    is Payment -> when {
        t.closeTo != null -> SummaryPart.Text("Sends ${algoAmount(t.amount).text()} to ${AlgoAddress.encode(t.receiver)}.")
        else -> SummaryPart.Send(
            trimmedAmount(t.amount.toBig(), MICRO_ALGO_DECIMALS),
            "ALGO",
            signerAddress,
            AlgoAddress.encode(t.receiver),
        )
    }
    is AssetTransfer -> assetSummary(t, asset)
}

/**
 * The summary of an asset transfer. An opt-in adds the asset to the account. An opt-out removes it and sends what it holds to the
 * close-to address. Any other transfer that also closes the holding says where the rest goes. An asset the catalog does not name is
 * called by its ID, and its amount is in base units.
 */
private fun AlgorandRequestReview.assetSummary(txn: AssetTransfer, asset: Asa?): SummaryPart {
    val name = asset?.unitName ?: "asset ${txn.assetId}"
    val receiver = AlgoAddress.encode(txn.receiver)
    val closeTo = txn.closeTo?.let { AlgoAddress.encode(it) }
    return when {
        txn.isOptIn -> SummaryPart.Text("Opt in to $name.")
        txn.isOptOut && closeTo != null -> SummaryPart.Text("Opt out of $name, remaining balance goes to $closeTo.")
        closeTo != null -> {
            val amount = if (asset == null) "${txn.amount} base units of asset ${txn.assetId}" else assetAmount(txn.amount, asset).text()
            SummaryPart.Text("Sends $amount to $receiver. The rest goes to $closeTo, and the asset is removed.")
        }
        else -> {
            val from = txn.assetSender?.let { AlgoAddress.encode(it) } ?: signerAddress
            if (asset == null) {
                SummaryPart.Send(txn.amount.toString(), "base units of asset ${txn.assetId}", from, receiver)
            } else {
                SummaryPart.Send(trimmedAmount(txn.amount.toBig(), asset.decimals), asset.unitName, from, receiver)
            }
        }
    }
}

/**
 * The rows of the Transaction section, in order: the sender, the origin the wallet names, what the transaction moves, its fee, its rounds,
 * lease, group, note and close-to.
 */
private fun AlgorandRequestReview.rows(chain: ChainId): List<ReviewRow> = buildList {
    add(ReviewRow("Sender", DisplayValue.Address(chain, signerAddress), Fields.sender))
    origin?.let { add(ReviewRow(CLAIMED_ORIGIN_LABEL, DisplayValue.Text(it), Fields.origin, notes = listOf(ORIGIN_NOTE))) }
    when (val t = txn) {
        is Payment -> {
            add(ReviewRow("Receiver", addressOf(chain, t.receiver), Fields.receiver))
            add(ReviewRow("Amount", algoAmount(t.amount), Fields.amount))
        }
        is AssetTransfer -> {
            add(ReviewRow("Asset", DisplayValue.Text(assetName(t.assetId, asset)), Fields.assetId))
            add(ReviewRow("Asset ID", DisplayValue.Number(t.assetId.toBig()), Fields.assetId))
            // An opt-in moves no units, and its receiver is the sender itself, so it shows neither an amount nor a receiver.
            if (!t.isOptIn) {
                add(ReviewRow("Amount", assetAmount(t.amount, asset), Fields.amount))
                add(ReviewRow("Receiver", addressOf(chain, t.receiver), Fields.receiver))
            }
        }
    }
    add(ReviewRow("Fee", algoAmount(txn.fee), Fields.fee))
    add(ReviewRow("Valid from Round", DisplayValue.Number(txn.firstValid.toBig()), Fields.firstValid))
    add(ReviewRow("Valid until Round", DisplayValue.Number(txn.lastValid.toBig()), Fields.lastValid))
    txn.lease?.let { add(ReviewRow("Lease", DisplayValue.Bytes(it), Fields.lease)) }
    txn.group?.let { add(ReviewRow("Group ID", DisplayValue.Bytes(it), Fields.group)) }
    noteValue(txn.note)?.let { add(ReviewRow("Note", it, Fields.note)) }
    closeTo?.let {
        val label = if (txn is Payment) "Close Remainder To" else "Close Holding To"
        add(ReviewRow(label, addressOf(chain, it), Fields.closeTo))
    }
}

/** The fields the parser read from this transaction: each one the encoding carries, since canonical msgpack leaves out a zero value. */
private fun AlgorandRequestReview.decodedFields(): Set<FieldPath> = buildSet {
    val t = txn
    add(Fields.type)
    add(Fields.sender)
    origin?.let { add(Fields.origin) }
    add(Fields.genesisId)
    add(Fields.genesisHash)
    if (t.fee != 0UL) add(Fields.fee)
    if (t.firstValid != 0UL) add(Fields.firstValid)
    if (t.lastValid != 0UL) add(Fields.lastValid)
    if (t.note.isNotEmpty()) add(Fields.note)
    if (t.lease != null) add(Fields.lease)
    if (t.group != null) add(Fields.group)
    if (t.rekeyTo != null) add(Fields.rekeyTo)
    when (t) {
        is Payment -> {
            add(Fields.receiver)
            if (t.amount != 0UL) add(Fields.amount)
            if (t.closeTo != null) add(Fields.closeTo)
        }
        is AssetTransfer -> {
            add(Fields.assetId)
            add(Fields.receiver)
            if (t.amount != 0UL) add(Fields.amount)
            if (t.closeTo != null) add(Fields.closeTo)
            if (t.assetSender != null) add(Fields.assetSender)
        }
    }
}

/** The decoded fields that the review does not show, with the reason it does not. */
private fun AlgorandRequestReview.excludedFields(): Map<FieldPath, String> = buildMap {
    put(Fields.type, "the title and the summary name the transaction type")
    put(Fields.genesisId, "the header names the network by its genesis ID")
    put(Fields.genesisHash, "the genesis hash is checked against the network, and a transaction for another network is refused")
    if ((txn as? AssetTransfer)?.isOptIn == true) {
        put(Fields.receiver, "an opt-in sends to the sender's own address, so the review shows no receiver")
    }
}

private fun addressOf(chain: ChainId, key: ByteArray): DisplayValue = DisplayValue.Address(chain, AlgoAddress.encode(key))

/** The note as text when every character can be read, as Solana messages are. Any other note shows its bytes as hex. Null when it is empty. */
private fun noteValue(note: ByteArray): DisplayValue? = when {
    note.isEmpty() -> null
    else -> printableText(note)?.let { DisplayValue.Text(it) } ?: DisplayValue.Bytes(note)
}

private fun algoAmount(microAlgos: ULong): DisplayValue.Amount = DisplayValue.Amount(microAlgos.toBig(), MICRO_ALGO_DECIMALS, "ALGO")

/** An asset amount in its unit, with exactly the asset's decimal places, when the catalog gives them. Otherwise in base units. */
private fun assetAmount(amount: ULong, asset: Asa?): DisplayValue.Amount =
    DisplayValue.Amount(amount.toBig(), asset?.decimals, asset?.unitName)

/** The name of an asset in a row: its unit name, with its name in brackets when the two differ, or "Unknown asset #id". */
private fun assetName(assetId: ULong, asset: Asa?): String = when {
    asset == null -> "Unknown asset #$assetId"
    asset.name.isEmpty() || asset.name == asset.unitName -> asset.unitName
    else -> "${asset.unitName} (${asset.name})"
}

private fun ULong.toBig(): BigInteger = BigInteger(toString())
