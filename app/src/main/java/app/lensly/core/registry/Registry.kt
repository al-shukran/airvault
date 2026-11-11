package app.lensly.core.registry

/**
 * Immutable lookup over the baked-in network and token catalogs, and the Registry Whitelist.
 *
 * Unknown chain IDs, tokens absent from the registry and whitelist misses return null; the vault treats null as a refusal.
 * A whitelist holds at most one entry per chain, contract and selector, so a lookup has one answer.
 */
class Registry internal constructor(
    networks: List<NetworkEntry>,
    tokens: List<TokenEntry>,
    whitelist: List<WhitelistEntry> = emptyList(),
    /** The catalog sequence the build ships. An installed package replaces the whitelist only when its sequence is higher. */
    val catalogSequence: Long = 0,
    /** True when an installed package was found but failed verification, so the baked whitelist is in force. */
    val installedUpdateUnverified: Boolean = false,
) {
    private val networkList: List<NetworkEntry> = networks.sortedBy { it.chainId }
    private val tokenList: List<TokenEntry> =
        tokens.sortedWith(compareBy<TokenEntry>({ it.chainId }, { it.contract }))
    private val whitelistList: List<WhitelistEntry> =
        whitelist.sortedWith(compareBy<WhitelistEntry>({ it.chainId }, { it.contract }, { it.selector }))
    private val networksById: Map<Long, NetworkEntry> = networkList.associateBy { it.chainId }
    private val tokensByKey: Map<Pair<Long, String>, TokenEntry> =
        tokenList.associateBy { it.chainId to it.contract }
    private val whitelistByKey: Map<Triple<Long, String, String>, WhitelistEntry> =
        whitelistList.associateBy { Triple(it.chainId, it.contract, it.selector) }

    init {
        require(networksById.size == networkList.size) { "duplicate chainId in registry" }
        require(tokensByKey.size == tokenList.size) { "duplicate token in registry" }
        for (token in tokenList) {
            require(networksById.containsKey(token.chainId)) {
                "token ${token.symbol} references unknown chainId ${token.chainId}"
            }
        }
        require(whitelistByKey.size == whitelistList.size) { "duplicate whitelist entry in registry" }
        for (entry in whitelistList) {
            require(networksById.containsKey(entry.chainId)) {
                "whitelist entry ${entry.signature} references unknown chainId ${entry.chainId}"
            }
        }
    }

    /** The network for [chainId], or null when the chain is not in the registry. */
    fun network(chainId: Long): NetworkEntry? = networksById[chainId]

    /** The token at [contract] on [chainId]. [contract] is matched case-insensitively. */
    fun token(chainId: Long, contract: String): TokenEntry? =
        tokensByKey[chainId to contract.lowercase()]

    /**
     * The whitelist entry that approves [selector] on [contract] on [chainId], or null when that pair is not on the whitelist.
     * [contract] is matched case-insensitively, and [selector] is 0x and 8 hexadecimal digits.
     */
    fun whitelist(chainId: Long, contract: String, selector: String): WhitelistEntry? =
        whitelistByKey[Triple(chainId, contract.lowercase(), selector.lowercase())]

    fun networks(): List<NetworkEntry> = networkList

    fun tokens(): List<TokenEntry> = tokenList

    fun whitelistEntries(): List<WhitelistEntry> = whitelistList

    /**
     * This registry plus [additionalNetworks] and [additionalTokens], used to merge an installed package. The whitelist is kept.
     * Throws IllegalArgumentException when an addition reuses a chain ID or token already present, so baked
     * entries are never replaced.
     */
    fun withAdditions(additionalNetworks: List<NetworkEntry>, additionalTokens: List<TokenEntry>): Registry =
        Registry(
            networkList + additionalNetworks,
            tokenList + additionalTokens,
            whitelistList,
            catalogSequence,
            installedUpdateUnverified,
        )

    /**
     * This registry with its whitelist replaced by [entries]. A signed package replaces the whole whitelist, so the entries
     * that were here before are gone.
     */
    fun withWhitelist(entries: List<WhitelistEntry>): Registry =
        Registry(networkList, tokenList, entries, catalogSequence, installedUpdateUnverified)

    /**
     * This registry with the flag that says an installed package could not be verified. The baked whitelist stays in force,
     * and Settings shows that the built-in catalog is in use.
     */
    fun withInstalledUpdateUnverified(): Registry =
        Registry(networkList, tokenList, whitelistList, catalogSequence, installedUpdateUnverified = true)
}
