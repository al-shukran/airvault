package app.lumio.data.config

/** The saved settings of [id], or the defaults when it has none. */
fun configOf(configs: Map<String, ProviderConfig>, id: String): ProviderConfig = configs[id] ?: ProviderConfig()

/** The settings with [id] switched [on] or off. */
fun withEnabled(configs: Map<String, ProviderConfig>, id: String, on: Boolean): Map<String, ProviderConfig> =
    configs + (id to configOf(configs, id).copy(enabled = on))

/** The settings with [key] as the API key of [id], trimmed. A blank key removes the saved one. */
fun withApiKey(configs: Map<String, ProviderConfig>, id: String, key: String): Map<String, ProviderConfig> =
    configs + (id to configOf(configs, id).copy(apiKey = key.trim().ifEmpty { null }))

/**
 * The settings with [id] moved one place up or down within [group], the group's listing order. The moved group is renumbered from 0 in its new order,
 * and the other providers keep their priorities. The settings are unchanged when [id] cannot move.
 */
fun withMoved(configs: Map<String, ProviderConfig>, group: List<String>, id: String, up: Boolean): Map<String, ProviderConfig> {
    val priorities = movedPriorities(orderedIds(group, configs), id, up) ?: return configs
    return configs + priorities.mapValues { (providerId, priority) -> configOf(configs, providerId).copy(priority = priority) }
}
