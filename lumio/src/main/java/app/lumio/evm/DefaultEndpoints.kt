package app.lumio.evm

/**
 * The default public RPC address and explorer address of each chain in the registry, and of the two debug testnets. The registry keeps explorer
 * names only, so the explorer addresses for the link-out live here.
 *
 * Sources: the chainid.network chains.json list, fetched on 2026-11-15. For each chain, the first RPC from its list that has no key placeholder
 * was asked for eth_chainId, eth_getBalance and eth_getTransactionCount at the latest block, and it is kept only when all three answered and
 * eth_chainId named the chain's own ID. A chain with no endpoint that passed has no default, and its owner sets one in EVM settings. Public
 * endpoints come and go, so these are a starting point, not a promise. HyperEVM (999) takes its RPC from
 * tools/registry-data/manual-networks.json and has no explorer: the chainid.network entry for 999 is another network.
 */
internal data class DefaultEndpoint(val chainId: Long, val rpc: String, val explorerUrl: String?)

internal object DefaultEndpoints {
    private val ALL: List<DefaultEndpoint> = listOf(
        DefaultEndpoint(1L, "https://ethereum-rpc.publicnode.com", "https://etherscan.io"),
        DefaultEndpoint(10L, "https://mainnet.optimism.io", "https://optimistic.etherscan.io"),
        DefaultEndpoint(25L, "https://evm.cronos.org", "https://explorer.cronos.org"),
        DefaultEndpoint(30L, "https://public-node.rsk.co", "https://explorer.rsk.co"),
        DefaultEndpoint(40L, "https://rpc.telos.net", "https://teloscan.io"),
        DefaultEndpoint(56L, "https://bsc-dataseed1.bnbchain.org", "https://bscscan.com"),
        DefaultEndpoint(61L, "https://geth-at.etc-network.info", "https://etc.blockscout.com"),
        DefaultEndpoint(100L, "https://rpc.gnosischain.com", "https://gnosisscan.io"),
        DefaultEndpoint(122L, "https://rpc.fuse.io", "https://explorer.fuse.io"),
        DefaultEndpoint(130L, "https://mainnet.unichain.org", "https://uniscan.xyz"),
        DefaultEndpoint(137L, "https://polygon.drpc.org", "https://polygonscan.com"),
        DefaultEndpoint(143L, "https://rpc.monad.xyz", "https://monadvision.com"),
        DefaultEndpoint(146L, "https://rpc.soniclabs.com", "https://sonicscan.org"),
        DefaultEndpoint(169L, "https://pacific-rpc.manta.network/http", "https://pacific-explorer.manta.network"),
        DefaultEndpoint(196L, "https://rpc.xlayer.tech", "https://www.oklink.com/xlayer"),
        DefaultEndpoint(232L, "https://rpc.lens.xyz", "https://explorer.lens.xyz"),
        DefaultEndpoint(250L, "https://fantom.drpc.org", "https://ftmscan.com"),
        DefaultEndpoint(252L, "https://rpc.frax.com", "https://fraxscan.com"),
        DefaultEndpoint(324L, "https://mainnet.era.zksync.io", "https://explorer.zksync.io"),
        DefaultEndpoint(369L, "https://rpc.pulsechain.com", "https://ipfs.scan.pulsechain.com"),
        DefaultEndpoint(388L, "https://mainnet.zkevm.cronos.org", "https://explorer.zkevm.cronos.org"),
        DefaultEndpoint(480L, "https://worldchain-mainnet.g.alchemy.com/public", "https://worldscan.org"),
        DefaultEndpoint(999L, "https://rpc.hyperliquid.xyz/evm", null),
        DefaultEndpoint(1088L, "https://andromeda.metis.io/?owner=1088", "https://andromeda-explorer.metis.io"),
        DefaultEndpoint(1101L, "https://zkevm-rpc.com", "https://zkevm.polygonscan.com"),
        DefaultEndpoint(1284L, "https://moonbeam.api.onfinality.io/public", "https://moonbeam.subscan.io"),
        DefaultEndpoint(1285L, "https://moonriver.api.onfinality.io/public", "https://moonriver.subscan.io"),
        DefaultEndpoint(1329L, "https://evm-rpc.sei-apis.com", "https://seiscan.io"),
        DefaultEndpoint(1868L, "https://rpc.soneium.org", "https://soneium.blockscout.com"),
        DefaultEndpoint(2020L, "https://api.roninchain.com/rpc", "https://app.roninchain.com"),
        DefaultEndpoint(2222L, "https://evm.kava.io", "https://kavascan.com"),
        DefaultEndpoint(2741L, "https://api.mainnet.abs.xyz", "https://abscan.org"),
        DefaultEndpoint(3338L, "https://quicknode1.peaq.xyz", "https://peaq.subscan.io"),
        DefaultEndpoint(4326L, "https://mainnet.megaeth.com/rpc", "https://mega.etherscan.io"),
        DefaultEndpoint(5000L, "https://rpc.mantle.xyz", "https://mantlescan.xyz"),
        DefaultEndpoint(8217L, "https://public-en.node.kaia.io", "https://kaiascan.io"),
        DefaultEndpoint(8453L, "https://mainnet.base.org/", "https://basescan.org"),
        DefaultEndpoint(9745L, "https://rpc.plasma.to", "https://plasmascan.to"),
        DefaultEndpoint(10143L, "https://testnet-rpc.monad.xyz", "https://testnet.monadexplorer.com"),
        DefaultEndpoint(13371L, "https://rpc.immutable.com", "https://explorer.immutable.com"),
        DefaultEndpoint(33139L, "https://rpc.apechain.com", "https://apescan.io"),
        DefaultEndpoint(42161L, "https://arb1.arbitrum.io/rpc", "https://arbiscan.io"),
        DefaultEndpoint(42170L, "https://nova.arbitrum.io/rpc", "https://nova-explorer.arbitrum.io"),
        DefaultEndpoint(42220L, "https://forno.celo.org", "https://celoscan.io"),
        DefaultEndpoint(43114L, "https://api.avax.network/ext/bc/C/rpc", "https://snowscan.xyz"),
        DefaultEndpoint(48900L, "https://mainnet.zircuit.com", "https://explorer.zircuit.com"),
        DefaultEndpoint(57073L, "https://rpc-gel.inkonchain.com", "https://explorer.inkonchain.com"),
        DefaultEndpoint(59144L, "https://rpc.linea.build", "https://lineascan.build"),
        DefaultEndpoint(60808L, "https://rpc.gobob.xyz", "https://explorer.gobob.xyz"),
        DefaultEndpoint(80094L, "https://rpc.berachain.com", "https://berascan.com"),
        DefaultEndpoint(167000L, "https://rpc.mainnet.taiko.xyz", "https://taikoscan.io"),
        DefaultEndpoint(534352L, "https://rpc.scroll.io", "https://scrollscan.com"),
        DefaultEndpoint(747474L, "https://rpc.katana.network", "https://katanascan.com"),
        DefaultEndpoint(7777777L, "https://rpc.zora.energy/", "https://explorer.zora.energy"),
        DefaultEndpoint(11155111L, "https://rpc.sepolia.ethpandaops.io", "https://sepolia.etherscan.io"),
        DefaultEndpoint(1313161554L, "https://mainnet.aurora.dev", "https://explorer.aurora.dev"),
    )

    private val byChainId: Map<Long, DefaultEndpoint> = ALL.associateBy { it.chainId }

    /** The default endpoint of [chainId], or null when no default is listed for it. */
    fun of(chainId: Long): DefaultEndpoint? = byChainId[chainId]
}
