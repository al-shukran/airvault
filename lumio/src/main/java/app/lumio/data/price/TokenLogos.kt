package app.lumio.data.price

import app.lumio.data.cache.Cache
import app.lumio.data.cache.CachePolicy
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TokenLogo
import app.lumio.data.sched.Scheduler
import java.util.concurrent.ConcurrentHashMap

/**
 * The logo images of tokens, from the price sources that serve them. An image read is kept in memory for the session and in the open vault's cache,
 * since a logo changes rarely, so a token's row or page draws it without asking again, after a restart too. A token that a source has no logo for is
 * remembered for the session, so it is not asked again. A token with no logo at any source is null, and its row shows its letter instead. A failure that
 * may pass is not remembered.
 *
 * Privacy: a logo request names the token's contract and never an address, as a price request names only the coin.
 *
 * @param scheduler the scheduler of the price sources. It routes each request, applies their rate limits and cooldowns, and fails over.
 * @param cache the open vault's cache, or null while the vault is locked.
 */
class TokenLogos(private val scheduler: Scheduler, private val cache: () -> Cache? = { null }) {
    private val images = ConcurrentHashMap<String, ByteArray>()
    private val noLogo = ConcurrentHashMap.newKeySet<String>()

    /** The image of the logo of the token at [contract] on [net], or null when no source has one or none answers now. */
    suspend fun imageOf(net: NetworkId, contract: String): ByteArray? {
        // An EVM contract is the same in any case; a Solana mint is not.
        val key = "${net.chain.caip2}|${if (net.isEvm) contract.lowercase() else contract}"
        images[key]?.let { return it }
        if (key in noLogo) return null
        val store = cache()
        store?.get(CachePolicy.LastKnown, "token-logo:$key")?.value?.let { return it.also { images[key] = it } }
        val image = try {
            scheduler.run(TokenLogo(net, contract)).bytes
        } catch (e: ProviderError) {
            if (e is ProviderError.NotFound) noLogo.add(key)
            return null
        }
        images[key] = image
        store?.put(CachePolicy.LastKnown, "token-logo:$key", image)
        return image
    }
}
