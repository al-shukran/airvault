package app.lumio.data.provider

import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.provider.blockchair.BlockchairProvider
import app.lumio.data.provider.blockscout.BlockscoutProvider
import app.lumio.data.provider.etherscan.EtherscanProvider
import app.lumio.data.provider.routescan.RoutescanProvider
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
    val KEY_LISTED: Set<String> = setOf("blockchair", "3xpl", "routescan", "etherscan", "blockscout", "1rpc", "ankr", "alchemy")

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
}
