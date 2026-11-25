package app.lumio.ui.settings

import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Trait
import app.lumio.data.sched.ProviderHealth

/** What the settings say about one provider now. */
sealed interface ProviderStatus {
    /** The owner switched the provider off. */
    data object Off : ProviderStatus

    /** The provider needs a key, and none is set. */
    data object NeedsKey : ProviderStatus

    /** No scheduler holds the provider, so no call can reach it. */
    data object NotActive : ProviderStatus

    /** Its circuit is open, and calls wait for a probe after [retryInMs]. */
    data class CircuitOpen(val retryInMs: Long) : ProviderStatus

    /** It is cooling down after a rate limit, for [inMs] more. */
    data class CoolingDown(val inMs: Long) : ProviderStatus

    /** It is healthy, with this [score]. Lower is healthier. */
    data class Healthy(val score: Double) : ProviderStatus

    /** No call has reached it yet. */
    data object Ready : ProviderStatus
}

/**
 * The status of one provider, from its traits, its settings and what its scheduler knows. A key-required provider with no key needs one, whether it is
 * switched on or off, so the status says why it does not work. A switched-off provider is [ProviderStatus.Off] whatever else holds. Then the scheduler
 * decides: an open circuit, a cooldown, a score, or no call yet.
 */
fun providerStatus(traits: Set<Trait>, config: ProviderConfig, health: ProviderHealth?): ProviderStatus = when {
    Trait.KEY_REQUIRED in traits && config.apiKey.isNullOrBlank() -> ProviderStatus.NeedsKey
    !config.enabled -> ProviderStatus.Off
    health == null -> ProviderStatus.NotActive
    health.circuitOpenMs > 0 -> ProviderStatus.CircuitOpen(health.circuitOpenMs)
    health.coolingMs > 0 -> ProviderStatus.CoolingDown(health.coolingMs)
    health.score != null -> ProviderStatus.Healthy(health.score)
    else -> ProviderStatus.Ready
}
