package app.lumio.data.provider

import app.lumio.data.provider.blockchair.BlockchairProvider
import app.lumio.data.provider.blockscout.BlockscoutProvider
import app.lumio.data.provider.etherscan.EtherscanProvider
import app.lumio.data.provider.routescan.RoutescanProvider
import app.lumio.data.provider.threexpl.ThreeXplProvider

/**
 * The DATA providers of EVM native balances. The EVM pool lists them ahead of the RPC providers, and the router offers a DATA provider first for a
 * balance read. So an RPC provider answers only when no DATA provider can serve the read at that moment.
 *
 * Blockchair's Bitcoin outputs are not listed in the Bitcoin scheduler yet. Its paging makes more than one HTTP call for each scheduler token, which
 * its keyless budget does not allow, so it stays out until that is fixed.
 */
object DataPlugins {
    /** The EVM balance providers. Etherscan is listed, but it serves nothing until a key is set. */
    fun evm(): List<Provider> = listOf(
        RoutescanProvider(),
        EtherscanProvider(),
        BlockscoutProvider(),
        BlockchairProvider(),
        ThreeXplProvider(),
    )
}
