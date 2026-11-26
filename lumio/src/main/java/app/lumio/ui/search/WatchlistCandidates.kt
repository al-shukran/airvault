package app.lumio.ui.search

import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.NativeCoins
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MarketCoin
import app.lumio.data.provider.MarketCoins
import app.lumio.search.SearchEntry
import app.lumio.search.matching
import app.lumio.watchlist.WatchedAsset

/**
 * One asset Add to Watchlist offers: the source's [coinId], the [asset] Lumio watches for it, the source's [name] and [symbol], its [rank] (null when
 * no market row was read for it), the URL of its logo [image], and how many other coins of the results share its name ([sameName]).
 */
data class Candidate(
    val coinId: String,
    val asset: WatchedAsset,
    val name: String,
    val symbol: String,
    val rank: MarketRank?,
    val image: String?,
    val sameName: Int = 0,
)

/**
 * The asset Lumio watches for [coin], or null when Lumio cannot watch it. A network's own coin is watched as that coin, and a token by its contract
 * on one of Lumio's networks: on Ethereum first, then Solana, then the other EVM networks by chain id, then Algorand. [evm] says whether Lumio has
 * the EVM mainnet of a chain id. An EVM contract is kept in lower case, a Solana mint as it is, and an Algorand asset only as its number.
 */
fun watchableAsset(coin: DirectoryCoin, evm: (Long) -> Boolean): WatchedAsset? {
    NativeCoins.networkOf(coin.id)?.takeIf { !it.isEvm || evm(Networks.evmIdOf(it.chain)) }?.let { return WatchedAsset(it) }
    return coin.contracts.entries.sortedBy { (network, _) -> preference(network) }.firstNotNullOfOrNull { (network, contract) ->
        val chain = ChainId(network)
        val id = NetworkId(chain, testnet = false)
        when {
            chain.namespace == Networks.EIP155 -> if (evm(Networks.evmIdOf(chain)) && EVM_CONTRACT.matches(contract)) WatchedAsset(id, contract.lowercase()) else null
            chain == Networks.SOL_MAINNET -> WatchedAsset(id, contract)
            chain == Networks.ALGO_MAINNET -> contract.takeIf { ASA_ID.matches(it) }?.let { WatchedAsset(id, it) }
            else -> null
        }
    }
}

/** The order networks are tried in for a token: Ethereum, Solana, the other EVM networks by chain id, then Algorand. */
private fun preference(network: String): Long = when (network) {
    Networks.evm(1).caip2 -> 0L
    Networks.SOL_MAINNET.caip2 -> 1L
    Networks.ALGO_MAINNET.caip2 -> Long.MAX_VALUE
    else -> network.substringAfter(':').toLongOrNull()?.let { it + 2L } ?: (Long.MAX_VALUE - 1)
}

private val EVM_CONTRACT = Regex("0x[0-9a-fA-F]{40}")
private val ASA_ID = Regex("[1-9][0-9]{0,19}")

/** The rank a market row gives: its place, or Unranked when the source ranks it nowhere. */
fun MarketCoin.marketRank(): MarketRank = rank?.let { MarketRank.Place(it) } ?: MarketRank.Unranked

/**
 * Top by Market Cap: the coins of [top] that Lumio can watch, in the source's order. A coin the directory does not list is watched only when it is a
 * network's own coin.
 */
fun topCandidates(top: MarketCoins, directory: CoinDirectory?, evm: (Long) -> Boolean): List<Candidate> {
    val listed = directory?.coins?.associateBy { it.id }.orEmpty()
    return top.coins.mapNotNull { coin ->
        val asset = watchableAsset(listed[coin.id] ?: DirectoryCoin(coin.id, coin.name, coin.symbol), evm) ?: return@mapNotNull null
        Candidate(coin.id, asset, coin.name, coin.symbol, coin.marketRank(), coin.image)
    }
}

/** The directory's coins that Lumio can watch, each with its asset, as search entries found by name and ticker. */
fun watchableEntries(directory: CoinDirectory, evm: (Long) -> Boolean): List<SearchEntry<Pair<DirectoryCoin, WatchedAsset>>> =
    directory.coins.mapNotNull { coin ->
        watchableAsset(coin, evm)?.let { SearchEntry(title = coin.name, detail = coin.symbol, terms = listOf(coin.name, coin.symbol), target = coin to it) }
    }

/** The coins of [entries] that [query] finds, as the search finds them: by name or ticker, in the directory's order. */
fun searchMatches(query: String, entries: List<SearchEntry<Pair<DirectoryCoin, WatchedAsset>>>): List<Pair<DirectoryCoin, WatchedAsset>> =
    matching(query, entries).map { it.target }

/**
 * The results of a search: one row per name, the best ranked of the coins that share it, with the count of the others. Rows go by rank, the unranked
 * after the ranked, and a coin with no market row read last. [markets] are the market rows read for the matches, by coin id.
 */
fun searchCandidates(matches: List<Pair<DirectoryCoin, WatchedAsset>>, markets: Map<String, MarketCoin>): List<Candidate> {
    val candidates = matches.map { (coin, asset) ->
        val market = markets[coin.id]
        Candidate(coin.id, asset, coin.name, coin.symbol, market?.marketRank(), market?.image)
    }
    return candidates.groupBy { it.name.lowercase() }.values
        .map { group -> group.minWith(BY_RANK).copy(sameName = group.size - 1) }
        .sortedWith(BY_RANK)
}

/**
 * The names Lumio gives a coin where the market source's name differs: the locked kit titles USDC "USD Coin". Every other coin keeps the source's name.
 */
private val NAME_OVERRIDES: Map<String, String> = mapOf("usd-coin" to "USD Coin")

/** The name Lumio shows for the market source's coin [id] named [name]. */
fun coinDisplayName(id: String, name: String): String = NAME_OVERRIDES[id] ?: name

/**
 * The ticker and name of each coin of a directory, by the asset Lumio watches for it: a network's own coin as that coin, and a token on each of its
 * networks.
 */
private class NameIndex(val directory: CoinDirectory) {
    val names: Map<WatchedAsset, Pair<String, String>> = buildMap {
        directory.coins.forEach { coin ->
            val names = coin.symbol.uppercase() to coinDisplayName(coin.id, coin.name)
            NativeCoins.networkOf(coin.id)?.let { putIfAbsent(WatchedAsset(it), names) }
            coin.contracts.forEach { (network, contract) ->
                val chain = ChainId(network)
                val token = if (chain.namespace == Networks.EIP155) contract.lowercase() else contract
                putIfAbsent(WatchedAsset(NetworkId(chain, testnet = false), token), names)
            }
        }
    }
}

@Volatile
private var nameIndex: NameIndex? = null

/** The ticker and name that [directory] gives [asset], or null when it lists none. The index is built once for each directory. */
fun directoryNames(directory: CoinDirectory, asset: WatchedAsset): Pair<String, String>? {
    val index = nameIndex?.takeIf { it.directory === directory } ?: NameIndex(directory).also { nameIndex = it }
    return index.names[asset]
}

/** By rank: a place first, smallest first, then Unranked, then no rank read. */
private val BY_RANK: Comparator<Candidate> = compareBy { candidate ->
    when (val rank = candidate.rank) {
        is MarketRank.Place -> rank.place.toLong()
        MarketRank.Unranked -> Long.MAX_VALUE - 1
        null -> Long.MAX_VALUE
    }
}
