package app.lensly.core.registry

/**
 * Immutable lookup over the baked-in network and token catalogs.
 *
 * Unknown chain IDs and tokens absent from the registry return null; the vault treats null as a refusal.
 */
class Registry internal constructor(
    networks: List<NetworkEntry>,
    tokens: List<TokenEntry>,
) {
    private val networkList: List<NetworkEntry> = networks.sortedBy { it.chainId }
    private val tokenList: List<TokenEntry> =
        tokens.sortedWith(compareBy<TokenEntry>({ it.chainId }, { it.contract }))
    private val networksById: Map<Long, NetworkEntry> = networkList.associateBy { it.chainId }
    private val tokensByKey: Map<Pair<Long, String>, TokenEntry> =
        tokenList.associateBy { it.chainId to it.contract }

    init {
        require(networksById.size == networkList.size) { "duplicate chainId in registry" }
        require(tokensByKey.size == tokenList.size) { "duplicate token in registry" }
        for (token in tokenList) {
            require(networksById.containsKey(token.chainId)) {
                "token ${token.symbol} references unknown chainId ${token.chainId}"
            }
        }
    }

    /** The network for [chainId], or null when the chain is not in the registry. */
    fun network(chainId: Long): NetworkEntry? = networksById[chainId]

    /** The token at [contract] on [chainId]. [contract] is matched case-insensitively. */
    fun token(chainId: Long, contract: String): TokenEntry? =
        tokensByKey[chainId to contract.lowercase()]

    fun networks(): List<NetworkEntry> = networkList

    fun tokens(): List<TokenEntry> = tokenList

    /**
     * This registry plus [additionalNetworks] and [additionalTokens], used to merge an installed package.
     * Throws IllegalArgumentException when an addition reuses a chain ID or token already present, so baked
     * entries are never replaced.
     */
    fun withAdditions(additionalNetworks: List<NetworkEntry>, additionalTokens: List<TokenEntry>): Registry =
        Registry(networkList + additionalNetworks, tokenList + additionalTokens)
}
