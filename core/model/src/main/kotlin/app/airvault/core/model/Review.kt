package app.airvault.core.model

import java.math.BigDecimal
import java.math.BigInteger

/** A field the review shows or accounts for, dotted, for example `algorand.amount`. */
@JvmInline
value class FieldPath(val value: String) {
    init {
        require(value.isNotBlank()) { "field path is blank" }
    }

    override fun toString(): String = value
}

/**
 * What the owner reads before a request is signed (spec 3.2). The app builds it from a parsed request and the one review screen draws it.
 * [summary] is the sentence at the top, and [facts] are the short lines under it. [sections] hold the rows in display order.
 */
data class Review(
    val title: String,
    val chain: ChainId,
    val network: NetworkLabel,
    val summary: List<SummaryPart>,
    val facts: List<ReviewRow>,
    val sections: List<ReviewSection>,
    val warnings: List<Warning>,
    val coverage: Coverage,
)

/**
 * One piece of the summary sentence, in order, or a [Send] that is the whole summary. A [Counterparty] is named from the Address Book or
 * this device when either holds its address, and shown short otherwise. The names are the app's to add, so the core only says which address
 * is a counterparty.
 */
sealed interface SummaryPart {
    data class Text(val text: String) : SummaryPart

    /** Text in the monospace face, such as an amount inside the sentence. */
    data class Mono(val text: String) : SummaryPart

    data class Counterparty(val address: String) : SummaryPart

    /**
     * A send of one amount to one recipient, and the whole summary of it. [amount] is in whole units with no trailing zeros, [asset] names
     * its unit, and [from] and [to] are the addresses. The app writes it as a question and names each address from this device and the
     * Address Book, so the core says only what is sent and between which addresses.
     */
    data class Send(val amount: String, val asset: String, val from: String, val to: String) : SummaryPart
}

/** The summary as plain text, with each counterparty written as its address. Records and tests read a summary this way. */
fun List<SummaryPart>.text(): String = joinToString("") { part ->
    when (part) {
        is SummaryPart.Text -> part.text
        is SummaryPart.Mono -> part.text
        is SummaryPart.Counterparty -> part.address
        is SummaryPart.Send -> "Send ${part.amount} ${part.asset} from ${part.from} to ${part.to}?"
    }
}

/**
 * An amount in base units with [decimals] places, as whole units with no trailing zeros. It is exact: 400000 at six places is "0.4", and
 * 1000000 at six places is "1".
 */
fun trimmedAmount(baseUnits: BigInteger, decimals: Int): String =
    BigDecimal(baseUnits).movePointLeft(decimals).stripTrailingZeros().toPlainString()

/**
 * The network a review is for: its name, whether it is a testnet, and its raw detail under the name, such as the genesis ID. [row] is a row the
 * header shows under the name, such as the cluster a Solana request states.
 */
data class NetworkLabel(val name: String, val testnet: Boolean, val detail: String?, val row: ReviewRow? = null)

/**
 * A titled group of rows. [style] sets how the rows draw. [SectionStyle.Rows] shows each row as a label over its value. [SectionStyle.Steps]
 * numbers the rows in order, each row's label being one step's sentence, so a step row carries no value.
 */
data class ReviewSection(val heading: String, val rows: List<ReviewRow>, val style: SectionStyle = SectionStyle.Rows)

enum class SectionStyle { Rows, Steps }

/**
 * One label and the value it shows. [value] is null for a row that shows only its label: a heading over the rows under it, or a step. [field]
 * is the decoded field the row shows, which the coverage check reads. It is null for a row that shows no decoded field, so a heading or a step
 * never accounts for one. [depth] indents the row under the rows above it; 0 is the section's own level. [notes] are short sentences under
 * the value, for what the owner should know about it, such as whether Lensly signs an input.
 */
data class ReviewRow(
    val label: String,
    val value: DisplayValue?,
    val field: FieldPath?,
    val depth: Int = 0,
    val notes: List<String> = emptyList(),
)

/** A value a review shows. [text] is the one way it becomes text, so every screen and every record shows it alike. */
sealed interface DisplayValue {
    data class Address(val chain: ChainId, val address: String) : DisplayValue

    /** An amount in base units, written with [decimals] places and then [symbol]. Without decimals it is shown in base units only. */
    data class Amount(val baseUnits: BigInteger, val decimals: Int?, val symbol: String?) : DisplayValue

    data class Number(val value: BigInteger) : DisplayValue

    /**
     * Text the app has checked, such as a note whose every character can be read. [mono] sets a fact in the monospace face, for a shortened
     * address or a fee.
     */
    data class Text(val text: String, val mono: Boolean = false) : DisplayValue

    /** A message the owner reads whole, in the monospace face on a raised block, since its text is what is signed. */
    data class Message(val text: String) : DisplayValue

    /** Bytes, shown as hex and never as guessed text. */
    class Bytes(val bytes: ByteArray) : DisplayValue
}

/** The text a value shows on screen and in a record. A row with no value shows no text. */
fun DisplayValue?.text(): String = when (this) {
    null -> ""
    is DisplayValue.Address -> address
    is DisplayValue.Amount -> if (decimals == null) "$baseUnits base units" else scaled(baseUnits, decimals, symbol)
    is DisplayValue.Number -> value.toString()
    is DisplayValue.Text -> text
    is DisplayValue.Message -> text
    is DisplayValue.Bytes -> "0x" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun scaled(baseUnits: BigInteger, decimals: Int, symbol: String?): String =
    BigDecimal(baseUnits).movePointLeft(decimals).setScale(decimals).toPlainString() + (symbol?.let { " $it" } ?: "")

/**
 * A warning the review shows. [level] is the app's choice; the core enforces what each level means (spec 3.2). [acknowledge] is the label of a
 * check the owner ticks before the review can be signed, for a warning the app cannot resolve, such as an instruction it does not read. Warnings
 * with the same label share one check, so it is shown and ticked once. A DANGER with no label has no check to tick, so it stops the review.
 */
data class Warning(
    val level: Danger,
    val code: String,
    val message: String,
    val field: FieldPath? = null,
    val acknowledge: String? = null,
)

/** Whether this warning stops the review being signed (spec 3.2): a BLOCK, or a DANGER the owner has no check to tick for. */
val Warning.stopsSigning: Boolean get() = level == Danger.BLOCK || (level == Danger.DANGER && acknowledge == null)

/**
 * The fields a review accounts for (spec 3.2). [decoded] holds every field the parser read. Each one must be shown by a row, or be in
 * [excluded] with the reason it is not shown.
 */
data class Coverage(val decoded: Set<FieldPath>, val excluded: Map<FieldPath, String>)

/** The decoded fields that no row shows and no exclusion names. */
fun Review.unaccounted(): Set<FieldPath> {
    val shown = (facts + sections.flatMap { it.rows } + listOfNotNull(network.row)).mapNotNullTo(HashSet()) { it.field }
    return coverage.decoded - shown - coverage.excluded.keys
}

/**
 * The review checked for what stops it being signed (spec 3.2). A warning that [stopsSigning] is a BLOCK, so the screen refuses it as it refuses
 * any block. A decoded field no row shows adds a BLOCK warning of its own.
 */
fun Review.checked(): Review {
    val stopped = warnings.map { if (it.stopsSigning) it.copy(level = Danger.BLOCK) else it }
    if (unaccounted().isEmpty()) return copy(warnings = stopped)
    return copy(warnings = stopped + Warning(Danger.BLOCK, "coverage", "This request has fields the app did not show.", null))
}
