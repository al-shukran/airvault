package app.airvault.core.model

/**
 * What a review record keeps of one request (spec 8.6). Every chain app gives the same shape, so the record format is chain-blind. It holds
 * the chain and network, the title, the facts and rows the owner read in order, every address in full, the warnings shown and the checks
 * ticked. It holds no key material.
 */
data class RecordFacts(
    val chain: ChainId,
    val network: NetworkLabel,
    val title: String,
    /** The summary facts with a value, as label and text, in order. The network's header row comes first. */
    val facts: List<Pair<String, String>>,
    /** The section rows with a value, as label and text, in display order. Headings and steps show no value and are left out. */
    val rows: List<Pair<String, String>>,
    /** Every address the review shows, in full. */
    val addresses: List<String>,
    /** The warnings the owner was shown, with their level and message. */
    val warnings: List<Pair<Danger, String>>,
    /** The checks the owner ticked, by label. */
    val acknowledged: List<String>,
)

/** The record facts of this review, with the checks [acknowledged] by the owner (spec 8.6). */
fun Review.recordFacts(acknowledged: Collection<String> = emptyList()): RecordFacts {
    val header = listOfNotNull(network.row) + facts
    val rows = sections.flatMap { it.rows }
    return RecordFacts(
        chain = chain,
        network = network,
        title = title,
        facts = header.withValue(),
        rows = rows.withValue(),
        addresses = (header + rows).mapNotNull { (it.value as? DisplayValue.Address)?.address },
        warnings = warnings.map { it.level to it.message },
        acknowledged = acknowledged.toList(),
    )
}

private fun List<ReviewRow>.withValue(): List<Pair<String, String>> =
    mapNotNull { row -> row.value?.let { row.label to it.text() } }
