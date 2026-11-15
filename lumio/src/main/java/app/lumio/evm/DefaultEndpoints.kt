package app.lumio.evm

/**
 * The explorer address of each chain in the registry, and of the two debug testnets. The registry keeps explorer names only, so the explorer
 * addresses for the link-out live here. RPC addresses are not here: the providers in [app.lumio.data.rpc.RpcPlugins] serve each chain, and the
 * owner sets an address in EVM settings when none of them serves it.
 *
 * Source of the explorer addresses: the chainid.network chains.json list, fetched on 2026-11-15.
 */
internal data class DefaultEndpoint(val chainId: Long, val explorerUrl: String?)

internal object DefaultEndpoints {
    private val ALL: List<DefaultEndpoint> = listOf(
        DefaultEndpoint(1L, "https://etherscan.io"),
        DefaultEndpoint(10L, "https://optimistic.etherscan.io"),
        DefaultEndpoint(25L, "https://explorer.cronos.org"),
        DefaultEndpoint(30L, "https://explorer.rsk.co"),
        DefaultEndpoint(40L, "https://teloscan.io"),
        DefaultEndpoint(56L, "https://bscscan.com"),
        DefaultEndpoint(61L, "https://etc.blockscout.com"),
        DefaultEndpoint(100L, "https://gnosisscan.io"),
        DefaultEndpoint(122L, "https://explorer.fuse.io"),
        DefaultEndpoint(130L, "https://uniscan.xyz"),
        DefaultEndpoint(137L, "https://polygonscan.com"),
        DefaultEndpoint(143L, "https://monadvision.com"),
        DefaultEndpoint(146L, "https://sonicscan.org"),
        DefaultEndpoint(169L, "https://pacific-explorer.manta.network"),
        DefaultEndpoint(196L, "https://www.oklink.com/xlayer"),
        DefaultEndpoint(232L, "https://explorer.lens.xyz"),
        DefaultEndpoint(250L, "https://ftmscan.com"),
        DefaultEndpoint(252L, "https://fraxscan.com"),
        DefaultEndpoint(324L, "https://explorer.zksync.io"),
        DefaultEndpoint(369L, "https://ipfs.scan.pulsechain.com"),
        DefaultEndpoint(388L, "https://explorer.zkevm.cronos.org"),
        DefaultEndpoint(480L, "https://worldscan.org"),
        DefaultEndpoint(999L, null),
        DefaultEndpoint(1088L, "https://andromeda-explorer.metis.io"),
        DefaultEndpoint(1101L, "https://zkevm.polygonscan.com"),
        DefaultEndpoint(1284L, "https://moonbeam.subscan.io"),
        DefaultEndpoint(1285L, "https://moonriver.subscan.io"),
        DefaultEndpoint(1329L, "https://seiscan.io"),
        DefaultEndpoint(1868L, "https://soneium.blockscout.com"),
        DefaultEndpoint(2020L, "https://app.roninchain.com"),
        DefaultEndpoint(2222L, "https://kavascan.com"),
        DefaultEndpoint(2741L, "https://abscan.org"),
        DefaultEndpoint(3338L, "https://peaq.subscan.io"),
        DefaultEndpoint(4326L, "https://mega.etherscan.io"),
        DefaultEndpoint(5000L, "https://mantlescan.xyz"),
        DefaultEndpoint(8217L, "https://kaiascan.io"),
        DefaultEndpoint(8453L, "https://basescan.org"),
        DefaultEndpoint(9745L, "https://plasmascan.to"),
        DefaultEndpoint(10143L, "https://testnet.monadexplorer.com"),
        DefaultEndpoint(13371L, "https://explorer.immutable.com"),
        DefaultEndpoint(33139L, "https://apescan.io"),
        DefaultEndpoint(42161L, "https://arbiscan.io"),
        DefaultEndpoint(42170L, "https://nova-explorer.arbitrum.io"),
        DefaultEndpoint(42220L, "https://celoscan.io"),
        DefaultEndpoint(43114L, "https://snowscan.xyz"),
        DefaultEndpoint(48900L, "https://explorer.zircuit.com"),
        DefaultEndpoint(57073L, "https://explorer.inkonchain.com"),
        DefaultEndpoint(59144L, "https://lineascan.build"),
        DefaultEndpoint(60808L, "https://explorer.gobob.xyz"),
        DefaultEndpoint(80094L, "https://berascan.com"),
        DefaultEndpoint(167000L, "https://taikoscan.io"),
        DefaultEndpoint(534352L, "https://scrollscan.com"),
        DefaultEndpoint(747474L, "https://katanascan.com"),
        DefaultEndpoint(7777777L, "https://explorer.zora.energy"),
        DefaultEndpoint(11155111L, "https://sepolia.etherscan.io"),
        DefaultEndpoint(1313161554L, "https://explorer.aurora.dev"),
    )

    private val byChainId: Map<Long, DefaultEndpoint> = ALL.associateBy { it.chainId }

    /** The default endpoint of [chainId], or null when no default is listed for it. */
    fun of(chainId: Long): DefaultEndpoint? = byChainId[chainId]
}
