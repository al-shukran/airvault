package app.lumio.data.price

import app.lumio.data.net.NetworkId

/**
 * The DefiLlama chain prefix of each EVM mainnet: the `{chain}` of a `{chain}:{address}` coin id, which prices a token by its contract. The
 * DefiLlama free documentation (https://api-docs.defillama.com/llms-free.txt) gives the form and two prefixes, `ethereum` and `bsc`, and does not list
 * the rest. Each prefix here was read on 2026-11-16: `GET https://coins.llama.fi/prices/current/{prefix}:{contract}` answered a price, with the
 * token's symbol, for a token of that chain. A chain is added only after the same read. Chains that answered nothing that day are not listed:
 * Sonic (146), Kava (2222), Moonbeam (1284) and Fantom (250).
 *
 * Solana and Algorand mainnets take the prefixes `solana` and `algorand`: `solana:{mint}` and `algorand:{assetId}` answered a price on 2026-11-26
 * (USDC on both). A mint is case-sensitive.
 */
object DefiLlamaChains {
    /** The DefiLlama prefix of [net], or null when [net] is not a mainnet with a prefix. A testnet has none. */
    fun prefixOf(net: NetworkId): String? = when {
        net.testnet -> null
        net.isEvm -> PREFIXES[net.name]
        net.isSol -> "solana"
        net.isAlgo -> "algorand"
        else -> null
    }

    private val PREFIXES: Map<String, String> = mapOf(
        "1" to "ethereum",
        "10" to "optimism",
        "25" to "cronos",
        "56" to "bsc",
        "100" to "xdai",
        "130" to "unichain",
        "137" to "polygon",
        "143" to "monad",
        "480" to "wc",
        "999" to "hyperliquid",
        "1088" to "metis",
        "1329" to "sei",
        "1868" to "soneium",
        "5000" to "mantle",
        "8217" to "kaia",
        "8453" to "base",
        "9745" to "plasma",
        "42161" to "arbitrum",
        "42220" to "celo",
        "43114" to "avax",
        "57073" to "ink",
        "59144" to "linea",
        "80094" to "berachain",
        "534352" to "scroll",
    )
}
