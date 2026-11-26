package app.lumio.watchlist

import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.NativeCoins
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MarketCoins
import app.lumio.search.SearchEntry
import kotlinx.serialization.Serializable

/** One coin of the market source that Lumio can watch: the source's [id], [name] and [symbol], and the [asset] Lumio watches for it. */
@Serializable
data class IndexedCoin(val id: String, val name: String, val symbol: String, val asset: WatchedAsset)

/**
 * The coins of the market source's directory that Lumio can watch, read once a day and kept in memory only, never in the vault: the directory is
 * megabytes, while this index keeps one small entry per watchable coin. [entries] are what a search matches, by name and ticker.
 */
class CoinIndex(val coins: List<IndexedCoin>, val asOfMs: Long) {
    val byId: Map<String, IndexedCoin> = coins.associateBy { it.id }
    val entries: List<SearchEntry<IndexedCoin>> = coins.map { SearchEntry(title = it.name, detail = it.symbol, terms = listOf(it.name, it.symbol), target = it) }

    companion object {
        /** The index of [directory]: each coin Lumio can watch on the networks [evm] says it has. */
        fun of(directory: CoinDirectory, evm: (Long) -> Boolean): CoinIndex = CoinIndex(
            directory.coins.mapNotNull { coin -> watchableAsset(coin, evm)?.let { IndexedCoin(coin.id, coin.name, coin.symbol, it) } },
            directory.asOfMs,
        )
    }
}

/** One coin of Top by Market Cap: the [coin] Lumio watches for it, its [rank] by market cap (null when unranked) and its logo [image]. */
@Serializable
data class TopCoin(val coin: IndexedCoin, val rank: Int?, val image: String?)

/** Top by Market Cap as Lumio lists it: the source's coins by market cap that Lumio can watch, in its order, and when they were read. */
@Serializable
data class TopList(val coins: List<TopCoin>, val asOfMs: Long)

/**
 * Top by Market Cap: the coins of [top] that Lumio can watch, in the source's order. A coin [index] does not list is watched only when it is a network's
 * own coin, so with no index the list holds the coins alone.
 */
fun topList(top: MarketCoins, index: CoinIndex?, evm: (Long) -> Boolean): TopList = TopList(
    top.coins.mapNotNull { coin ->
        val indexed = index?.byId?.get(coin.id) ?: watchableAsset(DirectoryCoin(coin.id, coin.name, coin.symbol), evm)?.let { IndexedCoin(coin.id, coin.name, coin.symbol, it) }
        indexed?.let { TopCoin(it, coin.rank, coin.image) }
    },
    top.asOfMs,
)

/**
 * The asset Lumio watches for [coin], or null when Lumio cannot watch it. A network's own coin is watched as that coin, and a token by its contract
 * on one of Lumio's networks: on Ethereum first, then Solana, then the other EVM networks by chain id, then Algorand. [evm] says whether Lumio has
 * the EVM mainnet of a chain id. An EVM contract is kept in lower case, a Solana mint as it is, and an Algorand asset only as a number that fits its
 * 64-bit asset ID.
 */
fun watchableAsset(coin: DirectoryCoin, evm: (Long) -> Boolean): WatchedAsset? {
    NativeCoins.networkOf(coin.id)?.takeIf { !it.isEvm || evm(Networks.evmIdOf(it.chain)) }?.let { return WatchedAsset(it) }
    return coin.contracts.entries.sortedBy { (network, _) -> preference(network) }.firstNotNullOfOrNull { (network, contract) ->
        val chain = runCatching { ChainId(network) }.getOrNull() ?: return@firstNotNullOfOrNull null
        val id = NetworkId(chain, testnet = false)
        when {
            chain.namespace == Networks.EIP155 -> if (evm(Networks.evmIdOf(chain)) && EVM_CONTRACT.matches(contract)) WatchedAsset(id, contract.lowercase()) else null
            chain == Networks.SOL_MAINNET -> WatchedAsset(id, contract)
            chain == Networks.ALGO_MAINNET -> contract.takeIf { ASA_ID.matches(it) && it.toULongOrNull() != null }?.let { WatchedAsset(id, it) }
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
