package app.lumio.evm

import app.lensly.core.registry.Registry

/**
 * One chain Lumio lists: a registry network, or a custom chain the owner added. [defaultRpc] is the public endpoint a built-in chain
 * starts with, and it is null for a custom chain, whose RPC is set in [EvmSettings.rpc]. [explorerUrl] is null when no explorer is on file.
 */
data class EvmChain(
    val chainId: Long,
    val name: String,
    val symbol: String,
    val decimals: Int,
    val testnet: Boolean,
    val explorerUrl: String?,
    val defaultRpc: String?,
)

/** The chains Lumio lists: the registry's networks in chain-ID order, then the owner's custom chains. */
object EvmCatalog {
    /** Custom chains use the EVM default of 18 decimals, because the form does not ask for another value. */
    private const val CUSTOM_DECIMALS = 18

    fun chains(registry: Registry, settings: EvmSettings): List<EvmChain> {
        val builtIn = registry.networks().map { network ->
            val endpoint = DefaultEndpoints.of(network.chainId)
            EvmChain(
                chainId = network.chainId,
                name = network.name,
                symbol = network.nativeSymbol,
                decimals = network.nativeDecimals,
                testnet = network.testnet,
                // The registry names the explorer, and the endpoint table holds its address. Without a name in the registry, no link is shown.
                explorerUrl = if (network.explorerName != null) endpoint?.explorerUrl else null,
                defaultRpc = endpoint?.rpc,
            )
        }
        val builtInIds = builtIn.map { it.chainId }.toSet()
        val custom = settings.custom
            .filter { it.chainId !in builtInIds }
            .map { c ->
                EvmChain(
                    chainId = c.chainId,
                    name = c.name,
                    symbol = c.symbol,
                    decimals = CUSTOM_DECIMALS,
                    testnet = c.testnet,
                    explorerUrl = null,
                    defaultRpc = null,
                )
            }
        return builtIn + custom
    }

    /** The RPC address that [chain] reads from: the owner's address when one is set, else the chain's default. Null when neither exists. */
    fun rpcFor(chain: EvmChain, settings: EvmSettings): String? = settings.rpc[chain.chainId] ?: chain.defaultRpc
}
