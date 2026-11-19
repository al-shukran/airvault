package app.lumio.data.rpc

import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Trait

/**
 * The public RPC addresses that chainid.network lists for each chain: one or two per chain, all HTTPS and keyless. Source: the `rpc` list of each
 * chain in https://chainid.network/chains.json, read on 2026-11-15. Each address was asked for eth_chainId and answered with the chain's own ID on
 * that date. The first address of each chain is the one the default table held before the pool replaced it. The second address is a second public
 * endpoint of the same chain, so a call that fails on the first moves to it (see [EvmRpcProvider.execute]).
 *
 * The provider is FALLBACK_ONLY: the router offers it only when no other provider can serve the request. It makes no MEV or privacy claim. The
 * addresses were probed without a key. [table] is the chain-ID table; a test passes its own, with [allowLocalHttp] set for a local server.
 */
class ChainlistRpc(
    private val table: Map<String, List<String>> = ENDPOINTS,
    override val allowLocalHttp: Boolean = false,
) : EvmRpcProvider(ID, setOf(Trait.KEYLESS, Trait.FALLBACK_ONLY), PUBLIC_LIMITS) {
    override fun endpoints(net: NetworkId, cfg: ProviderConfig): List<String> = table[net.name].orEmpty()

    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? = endpoints(net, cfg).firstOrNull()

    companion object {
        /** The provider id. Its config is keyed by this id in the vault record. */
        const val ID = "chainlist"

        /** The addresses of each chain, keyed by chain ID. The first address is tried first. */
        val ENDPOINTS: Map<String, List<String>> = mapOf(
            "10143" to listOf("https://testnet-rpc.monad.xyz"),
            "1088" to listOf("https://andromeda.metis.io/?owner=1088", "https://metis-rpc.publicnode.com"),
            "122" to listOf("https://rpc.fuse.io", "https://fuse.drpc.org"),
            "1285" to listOf("https://moonriver.api.onfinality.io/public"),
            "13371" to listOf("https://rpc.immutable.com", "https://immutable-zkevm.drpc.org"),
            "143" to listOf("https://rpc.monad.xyz"),
            "167000" to listOf("https://rpc.mainnet.taiko.xyz", "https://taiko-rpc.publicnode.com"),
            "1868" to listOf("https://rpc.soneium.org"),
            "196" to listOf("https://rpc.xlayer.tech", "https://xlayerrpc.okx.com"),
            "2020" to listOf("https://api.roninchain.com/rpc"),
            "232" to listOf("https://rpc.lens.xyz"),
            "252" to listOf("https://rpc.frax.com", "https://fraxtal-rpc.publicnode.com"),
            "2741" to listOf("https://api.mainnet.abs.xyz"),
            "30" to listOf("https://public-node.rsk.co", "https://mycrypto.rsk.co"),
            "33139" to listOf("https://rpc.apechain.com"),
            "3338" to listOf("https://quicknode1.peaq.xyz", "https://quicknode2.peaq.xyz"),
            "369" to listOf("https://rpc.pulsechain.com", "https://pulsechain-rpc.publicnode.com"),
            "388" to listOf("https://mainnet.zkevm.cronos.org"),
            "42170" to listOf("https://nova.arbitrum.io/rpc", "https://arbitrum-nova-rpc.publicnode.com"),
            "480" to listOf("https://worldchain-mainnet.g.alchemy.com/public", "https://480.rpc.thirdweb.com"),
            "48900" to listOf("https://mainnet.zircuit.com"),
            "57073" to listOf("https://rpc-gel.inkonchain.com", "https://rpc-qnd.inkonchain.com"),
            "60808" to listOf("https://rpc.gobob.xyz"),
            "61" to listOf("https://geth-at.etc-network.info", "https://etc.etcdesktop.com"),
            "747474" to listOf("https://rpc.katana.network", "https://rpc.katanarpc.com"),
            "7777777" to listOf("https://rpc.zora.energy/"),
            "9745" to listOf("https://rpc.plasma.to"),
        )
    }
}
