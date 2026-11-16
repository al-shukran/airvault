package app.lumio.data.provider

import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.provider.blockchair.BlockchairProvider
import app.lumio.data.provider.blockscout.BlockscoutProvider
import app.lumio.data.provider.coingecko.CoinGeckoProvider
import app.lumio.data.provider.defillama.DefiLlamaProvider
import app.lumio.data.provider.etherscan.EtherscanProvider
import app.lumio.data.provider.routescan.RoutescanProvider
import app.lumio.data.provider.solana.CustomSolanaRpc
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SolanaRpc
import app.lumio.data.provider.threexpl.ThreeXplProvider
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.rpc.RpcPlugins

/**
 * The providers the settings list, each group in the order it is listed when no priority is set. The chain-data providers come first and the
 * JSON-RPC providers after them, with the owner's custom RPC last.
 */
object ProviderCatalog {
    /**
     * The providers whose settings take an API key, and whose plugins send it. dRPC takes none: its keyed endpoint format could not be confirmed from
     * its docs when this was checked.
     */
    val KEY_LISTED: Set<String> = setOf("blockchair", "3xpl", "routescan", "etherscan", "blockscout", "1rpc", "ankr", "alchemy", "coingecko")

    /** The PRICE providers, in their listed order: CoinGecko, which needs a demo key, then DefiLlama, which is keyless. */
    fun priceProviders(): List<Provider> = listOf(CoinGeckoProvider(), DefiLlamaProvider())

    /**
     * The priorities the price providers have until the owner moves them. CoinGecko comes first, so a keyed CoinGecko is asked before DefiLlama, and
     * DefiLlama answers when CoinGecko cannot. With no key CoinGecko is not eligible, so DefiLlama alone answers.
     */
    private val PRICE_PRIORITIES: Map<String, Int> = mapOf("coingecko" to 0, "defillama" to 1)

    /**
     * The settings with the price defaults under [saved]. A saved setting of a price provider replaces its default whole, so every change to the
     * price group must start from this map, or a default priority would be written as 0 and tie with the other provider.
     */
    fun withDefaults(saved: Map<String, ProviderConfig>): Map<String, ProviderConfig> =
        PRICE_PRIORITIES.mapValues { (_, priority) -> ProviderConfig(priority = priority) } + saved

    /** The chain-data providers, in their listed order. */
    fun dataProviders(): List<Provider> = listOf(
        BlockchairProvider(),
        ThreeXplProvider(),
        RoutescanProvider(),
        EtherscanProvider(),
        BlockscoutProvider(),
        MempoolProvider(),
        BlockstreamProvider(),
    )

    /** The JSON-RPC providers of the public pool, then the owner's custom RPC. [allowLocalHttp] is true only in a debug build. */
    fun rpcProviders(allowLocalHttp: Boolean): List<Provider> = RpcPlugins.pool() + CustomRpc(allowLocalHttp)

    /**
     * The Solana providers: the public devnet endpoint, then the owner's own devnet RPC. They serve the Solana family only, so no EVM read reaches them,
     * and the settings screens do not list them yet.
     */
    fun solanaProviders(allowLocalHttp: Boolean): List<Provider> = listOf(SolanaRpc(), CustomSolanaRpc(SOLANA_DEVNET.chain, allowLocalHttp))
}
