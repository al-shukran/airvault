package app.lumio.data.config

import kotlinx.serialization.Serializable

/** The request rate in [rps], the burst the bucket holds, and how many calls may run at once. */
@Serializable
data class Limits(val rps: Double, val burst: Int, val maxConcurrent: Int) {
    init {
        require(rps > 0 && burst >= 1 && maxConcurrent >= 1) { "A limit needs a positive rate, a burst of 1 or more, and 1 or more calls at once." }
    }
}

/**
 * One provider's settings. [priority] orders providers, lower first. [weight] splits calls among providers that share a priority. [apiKey] and
 * [baseUrl] can carry secrets, so [toString] never prints them.
 */
@Serializable
data class ProviderConfig(
    val enabled: Boolean = true,
    val priority: Int = 0,
    val weight: Int = 1,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val limits: Limits? = null,
) {
    init {
        require(weight >= 1) { "A weight is 1 or more." }
    }

    override fun toString(): String =
        "ProviderConfig(enabled=$enabled, priority=$priority, weight=$weight, " +
            "apiKey=${if (apiKey == null) "none" else "set"}, baseUrl=${if (baseUrl == null) "default" else "custom"}, limits=$limits)"
}
