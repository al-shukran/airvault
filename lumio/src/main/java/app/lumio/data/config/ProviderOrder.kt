package app.lumio.data.config

/**
 * The order the settings list one group of providers in: lower priority first, and the listing order for providers that tie. [ids] is the group's
 * listing order. The sort is stable, so a tie keeps its listing order.
 */
fun orderedIds(ids: List<String>, configs: Map<String, ProviderConfig>): List<String> = ids.sortedBy { configs[it]?.priority ?: 0 }
