package app.lensly.ui.sign

/**
 * What the review of typed data (EIP-712) shows. [domain] lists the members of the domain, with the network its chain names.
 * [summary] holds the facts of a Permit or Permit2 payload, and is empty for any other type. [fields] lists every member of the
 * message, nested, in the order the message holds them. [hasUnknownToken] is set when a Permit or Permit2 payload moves a token
 * that the registry does not hold, so the review asks the owner to check it.
 */
data class TypedReview(
    val domain: List<Pair<String, String>>,
    val summary: List<Pair<String, String>>,
    val fields: List<TypedRow>,
    val hasUnknownToken: Boolean = false,
)

/**
 * One line of a typed message. [depth] is how far the line sits inside its struct or array. A header line has no [value]: it names a
 * struct or an array, and its members follow it one level deeper. [type] is the EIP-712 type the line holds.
 */
data class TypedRow(
    val depth: Int,
    val label: String,
    val type: String,
    val value: String?,
)
