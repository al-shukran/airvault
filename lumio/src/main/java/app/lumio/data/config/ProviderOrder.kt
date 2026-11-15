package app.lumio.data.config

/**
 * The order the settings list one group of providers in: lower priority first, and the listing order for providers that tie. [ids] is the group's
 * listing order. The sort is stable, so a tie keeps its listing order.
 */
fun orderedIds(ids: List<String>, configs: Map<String, ProviderConfig>): List<String> = ids.sortedBy { configs[it]?.priority ?: 0 }

/**
 * The new priorities that move [id] one place up (toward the front) or down within [ordered], the group in its current order. The whole group is
 * numbered 0 .. n-1 in its new order, so no two providers of the group tie. Null when [id] is not in [ordered] or already sits at that end.
 */
fun movedPriorities(ordered: List<String>, id: String, up: Boolean): Map<String, Int>? {
    val from = ordered.indexOf(id)
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in ordered.indices) return null
    val next = ordered.toMutableList().apply {
        this[from] = ordered[to]
        this[to] = ordered[from]
    }
    return next.withIndex().associate { (rank, providerId) -> providerId to rank }
}
