package app.lumio.data.route

import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Kind
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.Trait

/** What routing needs from the scheduler's state. Both answers are read under the scheduler's lock. */
interface ProviderState {
    /** False while the provider is cooling down, its circuit is open, or its one half-open probe is out. */
    fun available(providerId: String, nowMs: Long): Boolean

    /** The provider's health score. Lower is healthier. */
    fun score(providerId: String, nowMs: Long): Double

    /** True while the provider is passed over after a recent failure. It goes after the others, and it serves when none of them can. */
    fun penalized(providerId: String, nowMs: Long): Boolean = false
}

/**
 * True when [p] may serve [op] on [net] at all under [configs]: it is enabled, it has a key if its traits require one, and it supports the request
 * with its config. Cooldowns and circuits are not checked here. See [ProviderState].
 */
internal fun isEligible(p: Provider, net: NetworkId, op: Op, configs: Map<String, ProviderConfig>): Boolean {
    val cfg = configs[p.id] ?: ProviderConfig()
    return cfg.enabled && (Trait.KEY_REQUIRED !in p.traits || !cfg.apiKey.isNullOrBlank()) && p.supports(net, op, cfg)
}

/**
 * Chooses the providers for one request, best first. A provider is a candidate when [isEligible] holds and [ProviderState] says it is available.
 * Then:
 * 1. for BROADCAST on an EVM network, while a candidate is MEV-protected, no other candidate is offered: a transaction never goes to a provider
 *    that does not protect it while a protected provider is healthy;
 * 2. a FALLBACK_ONLY provider is offered only when no other candidate is left, so it never serves while another provider can;
 * 3. a provider that this request already failed on transiently goes after the others, so a retry moves to the next candidate;
 * 4. a provider that [ProviderState.penalized] says failed recently goes after the others, so a hung provider is passed over at once;
 * 5. for balance reads, DATA providers before RPC providers;
 * 6. the priority in the config, lower first;
 * 7. the health score, then the id.
 * Providers that tie on 3 to 6 share the calls by smooth weighted round robin, so the config weights split the load.
 */
class Router(private val providers: List<Provider>) {
    private val rotation = HashMap<String, Long>()

    fun route(
        net: NetworkId,
        op: Op,
        configs: Map<String, ProviderConfig>,
        state: ProviderState,
        nowMs: Long,
        tried: Set<String>,
        excluded: Set<String>,
    ): List<Provider> {
        // An enabled owner's server ([Trait.OWN_SERVER]) takes its network for itself: while it is enabled, no public provider serves that network,
        // so the owner's addresses never reach a third party. When it is cooling, the call waits for it rather than going elsewhere.
        val found = providers.filter { p -> p.id !in excluded && isEligible(p, net, op, configs) }
        val eligible = found.filter { Trait.OWN_SERVER in it.traits }.ifEmpty { found }.filter { p -> state.available(p.id, nowMs) }
        val protectedOnly = op == Op.BROADCAST && net.isEvm && eligible.any { Trait.MEV_PROTECTED in it.traits }
        val offered = if (protectedOnly) eligible.filter { Trait.MEV_PROTECTED in it.traits } else eligible
        val others = offered.filter { Trait.FALLBACK_ONLY !in it.traits }
        val candidates = others.ifEmpty { offered }
        if (candidates.isEmpty()) return emptyList()
        val penalized = candidates.filter { state.penalized(it.id, nowMs) }.mapTo(HashSet()) { it.id }
        val ranked = candidates.sortedWith(
            compareBy<Provider>(
                { if (it.id in tried) 1 else 0 },
                { if (it.id in penalized) 1 else 0 },
                { kindRank(it, op) },
                { configs[it.id]?.priority ?: 0 },
                { state.score(it.id, nowMs) },
                { it.id },
            ),
        )
        val leader = ranked.first()
        val tie = ranked.takeWhile { rankOf(it, op, configs, tried, penalized) == rankOf(leader, op, configs, tried, penalized) }
        if (tie.size == 1) return ranked
        val chosen = pickByWeight(tie, net, op, configs)
        return listOf(chosen) + ranked.filter { it !== chosen }
    }

    private fun rankOf(p: Provider, op: Op, configs: Map<String, ProviderConfig>, tried: Set<String>, penalized: Set<String>): List<Int> =
        listOf(if (p.id in tried) 1 else 0, if (p.id in penalized) 1 else 0, kindRank(p, op), configs[p.id]?.priority ?: 0)

    private fun kindRank(p: Provider, op: Op): Int =
        if ((op == Op.NATIVE_BALANCE || op == Op.TOKEN_BALANCES) && p.kind == Kind.RPC) 1 else 0

    /** Smooth weighted round robin (the nginx scheme) over [tie]. Each call adds every weight to its provider's running total, and the largest total wins and gives back the sum of all weights. */
    private fun pickByWeight(tie: List<Provider>, net: NetworkId, op: Op, configs: Map<String, ProviderConfig>): Provider {
        val weights = tie.associateWith { (configs[it.id]?.weight ?: 1).toLong() }
        val total = weights.values.sum()
        var chosen = tie.first()
        var best = Long.MIN_VALUE
        for (p in tie) {
            val key = "$net|$op|${p.id}"
            val current = (rotation[key] ?: 0L) + weights.getValue(p)
            rotation[key] = current
            if (current > best) {
                best = current
                chosen = p
            }
        }
        val chosenKey = "$net|$op|${chosen.id}"
        rotation[chosenKey] = rotation.getValue(chosenKey) - total
        return chosen
    }
}
