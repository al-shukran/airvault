package app.lumio.data.provider

import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.algorand.AlgorandAlgod
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
import app.lumio.evm.EvmChain

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

    /** The providers that cannot work without an API key. They start switched off, until the owner saves a key on their Provider page. */
    private val KEY_REQUIRED_IDS: Set<String> by lazy {
        (dataProviders() + priceProviders() + rpcProviders(allowLocalHttp = false))
            .filter { Trait.KEY_REQUIRED in it.traits }
            .map { it.id }
            .toSet()
    }

    /**
     * The settings with the defaults under [saved]. A saved setting of a provider replaces its default whole, so every change to a defaulted provider must
     * start from this map, or a default would be written as the plain setting and tie with the other providers. The price providers take their priorities,
     * and the providers that need a key start switched off.
     */
    fun withDefaults(saved: Map<String, ProviderConfig>): Map<String, ProviderConfig> =
        PRICE_PRIORITIES.mapValues { (_, priority) -> ProviderConfig(priority = priority) } +
            KEY_REQUIRED_IDS.associateWith { ProviderConfig(enabled = false) } +
            saved

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
     * The Solana providers: the public endpoints (mainnet-beta and devnet), then the owner's own devnet RPC. They serve the Solana family only, so no EVM read reaches them,
     * and the settings screens do not list them yet.
     */
    fun solanaProviders(allowLocalHttp: Boolean): List<Provider> = listOf(SolanaRpc(), CustomSolanaRpc(SOLANA_DEVNET.name, allowLocalHttp))

    /**
     * The Algorand providers: the public Algonode endpoints, testnet and mainnet. They serve the Algorand family only, and the settings screens do not
     * list them yet, as with the Solana providers.
     */
    fun algorandProviders(): List<Provider> = listOf(AlgorandAlgod())

    /**
     * Whether [provider] serves [chain] for balance reads, as it does once the key it takes is set. Membership is read with a probe key, so a key-required
     * provider lists its chains before the owner sets one. [owner] is the owner's own chain addresses, which only the custom RPC reads, so it lists the
     * chains the owner set. [Provider.supports] only reads the probe; nothing is sent with it.
     */
    fun servesEvmChain(provider: Provider, chain: EvmChain, owner: Map<String, String> = emptyMap()): Boolean =
        provider.supports(
            NetworkId(Networks.evm(chain.chainId), chain.testnet),
            Ops.EVM_NATIVE_BALANCE,
            ProviderConfig(apiKey = "probe", chainUrls = owner),
        )

    /** The chains of [chains] that [provider] serves, as [servesEvmChain] reads them. */
    fun evmChainsOf(provider: Provider, chains: List<EvmChain>, owner: Map<String, String> = emptyMap()): List<EvmChain> =
        chains.filter { servesEvmChain(provider, it, owner) }
}
