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
    val summary: String,
    val facts: List<ReviewRow>,
    val sections: List<ReviewSection>,
    val warnings: List<Warning>,
    val coverage: Coverage,
)

/**
 * The network a review is for: its name, whether it is a testnet, and its raw detail under the name, such as the genesis ID. [row] is a row the
 * header shows under the name, such as the cluster a Solana request states.
 */
data class NetworkLabel(val name: String, val testnet: Boolean, val detail: String?, val row: ReviewRow? = null)

/** A titled group of rows. */
data class ReviewSection(val heading: String, val rows: List<ReviewRow>)

/**
 * One label and the value it shows. [field] is the decoded field the row shows, which the coverage check reads. [notes] are short
 * sentences under the value, for what the owner should know about it, such as whether Lensly signs an input.
 */
data class ReviewRow(val label: String, val value: DisplayValue, val field: FieldPath, val notes: List<String> = emptyList())

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

    /** Bytes, shown as hex and never as guessed text. */
    class Bytes(val bytes: ByteArray) : DisplayValue
}

/** The text a value shows on screen and in a record. */
fun DisplayValue.text(): String = when (this) {
    is DisplayValue.Address -> address
    is DisplayValue.Amount -> if (decimals == null) "$baseUnits base units" else scaled(baseUnits, decimals, symbol)
    is DisplayValue.Number -> value.toString()
    is DisplayValue.Text -> text
    is DisplayValue.Bytes -> "0x" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun scaled(baseUnits: BigInteger, decimals: Int, symbol: String?): String =
    BigDecimal(baseUnits).movePointLeft(decimals).setScale(decimals).toPlainString() + (symbol?.let { " $it" } ?: "")

/**
 * A warning the review shows. [level] is the app's choice; the core enforces what each level means (spec 3.2). [acknowledge] is the label of a
 * tick the owner sets before the review can be signed, for a warning the app cannot resolve, such as an instruction it does not read.
 */
data class Warning(
    val level: Danger,
    val code: String,
    val message: String,
    val field: FieldPath? = null,
    val acknowledge: String? = null,
)

/**
 * The fields a review accounts for (spec 3.2). [decoded] holds every field the parser read. Each one must be shown by a row, or be in
 * [excluded] with the reason it is not shown.
 */
data class Coverage(val decoded: Set<FieldPath>, val excluded: Map<FieldPath, String>)

/** The decoded fields that no row shows and no exclusion names. */
fun Review.unaccounted(): Set<FieldPath> {
    val shown = (facts + sections.flatMap { it.rows } + listOfNotNull(network.row)).mapTo(HashSet()) { it.field }
    return coverage.decoded - shown - coverage.excluded.keys
}

/** The review with a BLOCK warning when a decoded field is unaccounted for, so the review cannot be signed (spec 3.2). */
fun Review.checked(): Review {
    if (unaccounted().isEmpty()) return this
    return copy(warnings = warnings + Warning(Danger.BLOCK, "coverage", "This request has fields the app did not show.", null))
}
