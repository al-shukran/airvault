package app.lumio.data.config

/** The saved settings of [id], or the defaults when it has none. */
fun configOf(configs: Map<String, ProviderConfig>, id: String): ProviderConfig = configs[id] ?: ProviderConfig()

/** The settings with [id] switched [on] or off. */
fun withEnabled(configs: Map<String, ProviderConfig>, id: String, on: Boolean): Map<String, ProviderConfig> =
    configs + (id to configOf(configs, id).copy(enabled = on))

/**
 * The settings with [key] as the API key of [id], trimmed. A blank key removes the saved one. A provider that cannot work without its key
 * ([keyRequired]) is switched on by a key and off when the key is removed, so its switch agrees with its status.
 */
fun withApiKey(configs: Map<String, ProviderConfig>, id: String, key: String, keyRequired: Boolean = false): Map<String, ProviderConfig> {
    val keyed = configs + (id to configOf(configs, id).copy(apiKey = key.trim().ifEmpty { null }))
    return if (keyRequired) withEnabled(keyed, id, on = key.isNotBlank()) else keyed
}

/**
 * The settings with the providers of [order] reordered within [group]. [order] names some of the group's providers, in the order they should take. Each
 * takes the place it holds in the group's listing order, and the rest of the group keeps its places. The group is then renumbered from 0 in its new
 * order, and the providers outside it keep their priorities. A provider named twice, or one outside [group], is refused.
 */
fun withOrderWithin(configs: Map<String, ProviderConfig>, group: List<String>, order: List<String>): Map<String, ProviderConfig> {
    val listing = orderedIds(group, configs)
    require(order.all { it in listing } && listing.count { it in order } == order.size) { "The order names a provider outside the group, or one twice." }
    val next = order.iterator()
    val reordered = listing.map { if (it in order) next.next() else it }
    return configs + reordered.withIndex().associate { (rank, id) -> id to configOf(configs, id).copy(priority = rank) }
}
