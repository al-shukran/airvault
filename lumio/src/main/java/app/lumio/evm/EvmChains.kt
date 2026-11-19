package app.lumio.evm

import app.airvault.chains.evm.wallet.EvmWallet
import app.lensly.core.registry.Registry
import app.lumio.data.net.Networks

/**
 * One chain Lumio lists: a registry network, or a custom chain the owner added. [explorerUrl] is null when no explorer is on file. The RPC
 * address of a chain is not here: the pool serves it (see [app.lumio.data.rpc.RpcPlugins]), unless the owner set one in EVM settings.
 */
data class EvmChain(
    val chainId: Long,
    val name: String,
    val symbol: String,
    val decimals: Int,
    val testnet: Boolean,
    val explorerUrl: String?,
)

/** The chains Lumio lists: the registry's networks in chain-ID order, then the owner's custom chains. */
object EvmCatalog {
    /** Custom chains use the EVM default of 18 decimals, because the form does not ask for another value. */
    private const val CUSTOM_DECIMALS = 18

    /**
     * The primary network of the Testnet side, Monad testnet, or of the Mainnet side, Ethereum: an Ethereum account shows its native coin on it.
     * The EVM manifest marks it primary (see [EvmWallet.primaryNetwork]).
     */
    fun primaryChainId(testnet: Boolean): Long = Networks.evmIdOf(EvmWallet.primaryNetwork(testnet))

    fun chains(registry: Registry, settings: EvmSettings): List<EvmChain> {
        val builtIn = registry.networks().map { network ->
            EvmChain(
                chainId = network.chainId,
                name = network.name,
                symbol = network.nativeSymbol,
                decimals = network.nativeDecimals,
                testnet = network.testnet,
                // The registry names the explorer, and the endpoint table holds its address. Without a name in the registry, no link is shown.
                explorerUrl = if (network.explorerName != null) DefaultEndpoints.of(network.chainId)?.explorerUrl else null,
            )
        }
        val builtInIds = builtIn.map { it.chainId }.toSet()
        val custom = settings.custom
            .filter { it.chainId !in builtInIds }
            .map { c -> EvmChain(c.chainId, c.name, c.symbol, CUSTOM_DECIMALS, c.testnet, explorerUrl = null) }
        return primaryTestnetFirst(builtIn + custom)
    }

    /** A stable sort: the primary testnet moves to the front of the testnet side, and every other chain keeps its place. */
    internal fun primaryTestnetFirst(chains: List<EvmChain>): List<EvmChain> {
        val primary = primaryChainId(testnet = true)
        return chains.sortedBy { if (it.testnet && it.chainId == primary) 0 else 1 }
    }
}
