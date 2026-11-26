package app.lumio.data.price

import app.lumio.data.net.NetworkId
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.TokenLogo
import app.lumio.data.sched.Scheduler
import java.util.concurrent.ConcurrentHashMap

/**
 * The logo images of ERC-20 tokens, from the price sources that serve them. An image read this session is kept in memory, since a logo changes
 * rarely, so the page of a token does not read it again. A token that a source has no logo for is remembered too, so its page does not ask again.
 * A token with no logo at any source is null, and its page shows its letter instead. A failure that may pass is not remembered.
 *
 * Privacy: a logo request names the token's contract and never an address, as a price request names only the coin.
 *
 * @param scheduler the scheduler of the price sources. It routes each request, applies their rate limits and cooldowns, and fails over.
 */
class TokenLogos(private val scheduler: Scheduler) {
    private val images = ConcurrentHashMap<String, ByteArray>()
    private val noLogo = ConcurrentHashMap.newKeySet<String>()

    /** The image of the logo of the token at [contract] on [net], or null when no source has one or none answers now. */
    suspend fun imageOf(net: NetworkId, contract: String): ByteArray? {
        val key = "${net.chain.caip2}|${contract.lowercase()}"
        images[key]?.let { return it }
        if (key in noLogo) return null
        val image = try {
            scheduler.run(TokenLogo(net, contract)).bytes
        } catch (e: ProviderError) {
            if (e is ProviderError.NotFound) noLogo.add(key)
            return null
        }
        images[key] = image
        return image
    }
}
