package app.airvault.chains.bitcoin.signer

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
import app.airvault.core.model.text
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The decimal places of a satoshi: one BTC is 10^8 satoshis. */
private const val SAT_DECIMALS = 8

/** The code of the warning on unverified amounts. The owner ticks it before a request that needs confirmation can be signed. */
const val UNVERIFIED_AMOUNTS_CODE = "bitcoin.unverified-amounts"

/** The tick the owner gives before a request with unverified amounts can be signed. Set only when the review asks for it. */
private const val UNVERIFIED_CHECK = "I have checked the unverified input amounts"

private const val UNKNOWN_FEE_NOTE = "Unknown until every input amount is verified or confirmed."
private const val SIZE_NOTE = "Estimated from the unsigned request, so the signed size may differ by a few vbytes."
private const val NOT_ENFORCED_NOTE = "Not enforced, because every input is final."
private const val NO_ADDRESS_NOTE = "No address. The script is not paid to an address."

private val FEE = FieldPath("bitcoin.fee")
private val FEE_RATE = FieldPath("bitcoin.fee-rate")
private val TOTAL = FieldPath("bitcoin.total")
private val CHANGE = FieldPath("bitcoin.change")
private val SCRIPT_TYPE = FieldPath("bitcoin.script-type")
private val SIZE = FieldPath("bitcoin.size")
private val LOCK_TIME = FieldPath("bitcoin.locktime")

private fun inputField(index: Int, name: String) = FieldPath("bitcoin.input.$index.$name")

private fun outputField(index: Int, name: String) = FieldPath("bitcoin.output.$index.$name")

/**
 * The review of this request (spec 3.2), built for the one review renderer. [chain] is the Bitcoin network's chain ID and [network] names it.
 * [scriptType] is the Script Type of the account in words. [nameOf] gives an output address its Address Book name for the summary sentence.
 * The coverage check is applied, so a decoded field that no row shows is a BLOCK.
 */
fun BtcReview.toReview(
    chain: ChainId,
    network: NetworkLabel,
    scriptType: String,
    nameOf: (String) -> String? = { null },
): Review = Review(
    title = "Review",
    chain = chain,
    network = network,
    summary = listOf(SummaryPart.Text(sentence(nameOf))),
    facts = facts(),
    sections = listOf(inputsSection(), outputsSection(chain), transactionSection(scriptType)),
    warnings = warnings().map { warningOf(it, requiresConfirmation) },
    coverage = Coverage(decoded = decodedFields(), excluded = emptyMap()),
).checked()

/**
 * The summary sentence: the verb, the amount sent to others, and the counterparty. A single outside output names its counterparty by its
 * Address Book name, or else by its full address, and several are counted. A request that pays nothing outside the wallet says so.
 */
private fun BtcReview.sentence(nameOf: (String) -> String?): String {
    val others = outputs.filterNot { it.isOwnChange }
    return when (others.size) {
        0 -> "Pay nothing outside this wallet"
        1 -> {
            val address = others.single().address
            val counterparty = address?.let { nameOf(it) ?: it } ?: "a script with no address"
            "Send ${btcShort(others.single().amountSats)} to $counterparty"
        }
        else -> "Send ${btcShort(sentToOthersSats())} to ${others.size} addresses"
    }
}

/**
 * The key/value list under the sentence. The fee, the fee rate and the total are unknown while an input amount is unverified, and they say
 * so in words. The change back is "None" when the request returns no change.
 */
private fun BtcReview.facts(): List<ReviewRow> {
    val fee = feeSats
    val rate = feeRateSatPerVb
    val total = totalSats()
    val change = changeBackSats()
    return listOf(
        ReviewRow("Network Fee", fee?.let(::amount) ?: DisplayValue.Text("Unknown"), FEE),
        ReviewRow("Fee Rate", DisplayValue.Text(rate?.let { String.format(Locale.ROOT, "%.1f sat/vB", it) } ?: "Unknown"), FEE_RATE),
        ReviewRow("Total", total?.let(::amount) ?: DisplayValue.Text("Unknown"), TOTAL),
        ReviewRow("Change Back", if (change > 0) amount(change) else DisplayValue.Text("None"), CHANGE),
    )
}

private fun BtcReview.inputsSection(): ReviewSection = ReviewSection(
    heading = "Inputs",
    rows = inputs.flatMap { input ->
        listOf(
            ReviewRow("Input ${input.index}", amount(input.amountSats), inputField(input.index, "amount"), notes = inputNotes(input)),
            ReviewRow("Sequence", DisplayValue.Text(sequenceText(input)), inputField(input.index, "sequence")),
        )
    },
)

private fun BtcReview.outputsSection(chain: ChainId): ReviewSection = ReviewSection(
    heading = "Outputs",
    rows = outputs.flatMap { output ->
        val address = output.address
        buildList {
            add(
                ReviewRow(
                    "Output ${output.index}",
                    amount(output.amountSats),
                    outputField(output.index, "amount"),
                    notes = if (address == null) listOf(NO_ADDRESS_NOTE) else emptyList(),
                ),
            )
            if (address != null) {
                val change = if (output.isOwnChange) listOf("Your change, at change index ${output.changeIndex}") else emptyList()
                add(ReviewRow("Address", DisplayValue.Address(chain, address), outputField(output.index, "address"), notes = change))
            }
        }
    },
)

private fun BtcReview.transactionSection(scriptType: String): ReviewSection = ReviewSection(
    heading = "Transaction",
    rows = listOf(
        ReviewRow("Script Type", DisplayValue.Text(scriptType), SCRIPT_TYPE),
        ReviewRow(
            "Fee",
            DisplayValue.Text(feeSats?.let { "$it sat" } ?: "Unknown"),
            FEE,
            notes = if (feeSats == null) listOf(UNKNOWN_FEE_NOTE) else emptyList(),
        ),
        ReviewRow("Estimated Size", DisplayValue.Text("$estimatedVsize vB"), SIZE, notes = listOf(SIZE_NOTE)),
        ReviewRow(
            "Locktime",
            DisplayValue.Text(lockTimeText(lockTime)),
            LOCK_TIME,
            notes = if (lockTime != 0L && !lockTimeEnforced) listOf(NOT_ENFORCED_NOTE) else emptyList(),
        ),
    ),
)

/** The notes an input's row carries: whose key signs it, and whether its amount is verified. */
private fun inputNotes(input: BtcReviewInput): List<String> = buildList {
    add(
        if (input.ownerPath != null) "Owned by this wallet, so Lensly signs it"
        else "Not owned by this account, so Lensly does not sign it",
    )
    if (!input.amountVerified) add("Amount not verified against its previous transaction")
}

/** The fields the parser read from this request: each input's amount and sequence, each output's amount and address, and the locktime. */
private fun BtcReview.decodedFields(): Set<FieldPath> = buildSet {
    inputs.forEach { add(inputField(it.index, "amount")); add(inputField(it.index, "sequence")) }
    outputs.forEach { output ->
        add(outputField(output.index, "amount"))
        if (output.address != null) add(outputField(output.index, "address"))
    }
    add(LOCK_TIME)
}

private fun warningOf(warning: BtcWarning, confirmation: Boolean): Warning = when (warning) {
    is BtcWarning.UnverifiedAmounts -> Warning(
        Danger.DANGER,
        UNVERIFIED_AMOUNTS_CODE,
        warningText(warning),
        acknowledge = UNVERIFIED_CHECK.takeIf { confirmation },
    )
    is BtcWarning.ExternalInputs -> Warning(Danger.CAUTION, "bitcoin.external-inputs", warningText(warning))
    is BtcWarning.UnknownChange -> Warning(Danger.CAUTION, "bitcoin.unknown-change", warningText(warning))
    is BtcWarning.LockTime -> Warning(Danger.CAUTION, "bitcoin.lock-time", warningText(warning))
    is BtcWarning.HighFeeRate -> Warning(Danger.CAUTION, "bitcoin.high-fee-rate", warningText(warning))
    is BtcWarning.HighFeeShare -> Warning(Danger.CAUTION, "bitcoin.high-fee-share", warningText(warning))
}

/** The words a warning shows on the review and in its record. */
fun warningText(warning: BtcWarning): String = when (warning) {
    is BtcWarning.ExternalInputs -> "${warning.count} input(s) belong to another signer. Lensly does not sign them."
    is BtcWarning.UnverifiedAmounts ->
        "${warning.count} input amount(s) come from a witness UTXO only. They are not verified, so the fee is unknown until " +
            "they are confirmed."
    is BtcWarning.UnknownChange ->
        "${warning.count} output(s) name a key of this wallet but do not pay this account's change. Check where they go."
    is BtcWarning.HighFeeRate ->
        "The fee rate is ${String.format(Locale.ROOT, "%.1f", warning.satPerVb)} sat/vB, above your limit of " +
            "${warning.thresholdSatPerVb} sat/vB."
    is BtcWarning.HighFeeShare ->
        "The fee is ${String.format(Locale.ROOT, "%.1f", warning.percent)}% of the amount sent to others, above your limit " +
            "of ${warning.thresholdPercent}%."
    is BtcWarning.LockTime -> "The transaction cannot confirm before ${lockTimePoint(warning.lockTime)}."
}

/** The point a locktime holds the transaction until: "block N" for a block height, else the UTC date and time of a Unix time. */
internal fun lockTimePoint(lockTime: Long): String =
    if (lockTime < LOCK_TIME_THRESHOLD) "block $lockTime" else LOCK_TIME_DATE.format(Instant.ofEpochSecond(lockTime))

/** A locktime as the Transaction section shows it: "None" for zero, otherwise its point with a capital first letter. */
internal fun lockTimeText(lockTime: Long): String =
    if (lockTime == 0L) "None" else lockTimePoint(lockTime).replaceFirstChar { it.uppercaseChar() }

/** The sequence of an input and its replace-by-fee state, for the input's row. */
internal fun sequenceText(input: BtcReviewInput): String {
    val sequence = String.format(Locale.ROOT, "0x%08X", input.sequence)
    return "$sequence, replace-by-fee ${if (input.replaceableByFee) "on" else "off"}"
}

/** An amount in satoshis as BTC, with all eight decimal places. */
fun btcText(sats: Long): String = amount(sats).text()

/** An amount in BTC with no zeros at its end, for the summary sentence. Only the trailing zeros go, so the value stays exact. */
internal fun btcShort(sats: Long): String = btcText(sats).removeSuffix(" BTC").trimEnd('0').trimEnd('.') + " BTC"

private fun amount(sats: Long): DisplayValue.Amount = DisplayValue.Amount(BigInteger.valueOf(sats), SAT_DECIMALS, "BTC")

/** Sats the request sends to others: every output that is not the wallet's change. */
internal fun BtcReview.sentToOthersSats(): Long = outputs.filterNot { it.isOwnChange }.sumOf { it.amountSats }

/** Sats the request returns to this Account as change. */
internal fun BtcReview.changeBackSats(): Long = outputs.filter { it.isOwnChange }.sumOf { it.amountSats }

/** What leaves the wallet: the sats sent to others, plus the fee. Null while the fee is unknown. */
internal fun BtcReview.totalSats(): Long? = feeSats?.let { sentToOthersSats() + it }

private val LOCK_TIME_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC)
