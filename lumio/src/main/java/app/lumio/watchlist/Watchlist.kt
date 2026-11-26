package app.lumio.watchlist

import app.lensly.core.vault.VaultSession
import app.lumio.data.asset.AssetId
import app.lumio.data.config.UnreadableRecordException
import app.lumio.data.config.decodeOrRefuse
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The order of a watchlist: by market cap (the default), or by the 24 h change with the biggest gain first. */
enum class WatchlistSort(val label: String) {
    MARKET_CAP("Market Cap"),
    CHANGE_24H("24h Change"),
}

/**
 * One watched asset as the vault keeps it: its network, and its token id for a token: the lowercase contract on an EVM network, the mint on Solana
 * (case kept), the asset ID on Algorand. A null [contract] is the network's native coin.
 */
@Serializable
data class WatchedAsset(val network: NetworkId, val contract: String? = null) {
    val assetId: AssetId get() = contract?.let { AssetId.token(network, it) } ?: AssetId.Native(network)

    /** The asset as one string, the key of its [WatchedLabel]: its network's CAIP-2 id, the side, and its token id. */
    val key: String get() = "${network.chain.caip2}|${network.testnet}|${contract.orEmpty()}"
}

/**
 * What a watched asset is called and drawn with, kept with it when it is starred from Add to Watchlist: the market source's [symbol] and [name], and the
 * HTTPS URL of its logo [image]. Home and the asset page show them without reading the market source again.
 */
@Serializable
data class WatchedLabel(val symbol: String, val name: String, val image: String? = null)

/**
 * The watchlist of the app, the same whether or not a wallet is paired: its assets in the order they were starred, the order the list shows them in, and
 * the [labels] kept with the assets starred from Add to Watchlist, by [WatchedAsset.key].
 */
@Serializable
data class Watchlist(
    val assets: List<WatchedAsset> = emptyList(),
    val sort: WatchlistSort = WatchlistSort.MARKET_CAP,
    val labels: Map<String, WatchedLabel> = emptyMap(),
)

/** The assets the watchlist starts with, before the owner stars any: the mainnet coins that are most watched. */
val FIRST_RUN_ASSETS: List<WatchedAsset> = listOf(
    WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false)),
    WatchedAsset(NetworkId(Networks.evm(1), testnet = false)),
    WatchedAsset(NetworkId(Networks.SOL_MAINNET, testnet = false)),
    WatchedAsset(NetworkId(Networks.ALGO_MAINNET, testnet = false)),
)

/**
 * The assets in the order the list shows them. 24h Change puts the biggest gain first, and an asset with no change goes last. Market Cap keeps the
 * order the assets were starred in, because no market cap is read yet.
 */
fun Watchlist.ordered(change24h: (WatchedAsset) -> Double?): List<WatchedAsset> = when (sort) {
    WatchlistSort.MARKET_CAP -> assets
    WatchlistSort.CHANGE_24H -> assets.sortedByDescending(change24h)
}

/**
 * The watchlist with [asset] starred, with its [label] when one is given, or without it and its label when it is starred already. The other assets keep
 * the order they were starred in.
 */
fun Watchlist.toggled(asset: WatchedAsset, label: WatchedLabel? = null): Watchlist = if (asset in assets) {
    copy(assets = assets - asset, labels = labels - asset.key)
} else {
    copy(assets = assets + asset, labels = if (label == null) labels else labels + (asset.key to label))
}

/** The watchlist as the one vault record that holds it. */
@Serializable
private data class WatchlistRecord(val format: Int, val watchlist: Watchlist)

/** Reads and writes the watchlist as the vault record [RECORD], which is encrypted under the vault key like the other settings records. */
object WatchlistStore {
    /** The vault record that holds the watchlist. */
    const val RECORD = "lumio-watchlist"

    /**
     * The format of the record: 3 keeps one watchlist for the whole app, where 2 kept one per wallet. A record of an earlier format is not read, so the
     * list starts again from [FIRST_RUN_ASSETS], and the next change replaces it.
     */
    const val FORMAT = 3

    private val json = Json

    /**
     * The stored watchlist, or [FIRST_RUN_ASSETS] when none is stored yet, the record is of an earlier [FORMAT], or it does not parse. A record that does not
     * parse is left as it is.
     */
    fun read(session: VaultSession): Watchlist {
        val stored = try {
            load(session)
        } catch (e: UnreadableRecordException) {
            null
        }
        return stored ?: Watchlist(assets = FIRST_RUN_ASSETS)
    }

    /** Stores [watchlist]. A stored record that does not parse is left as it is, and this throws [UnreadableRecordException]. */
    fun write(session: VaultSession, watchlist: Watchlist) {
        load(session)
        session.putVaultRecord(RECORD, json.encodeToString(WatchlistRecord.serializer(), WatchlistRecord(FORMAT, watchlist)).encodeToByteArray())
    }

    /** The stored watchlist, or null when none is stored or it is of an earlier [FORMAT]. @throws UnreadableRecordException when the record does not parse. */
    private fun load(session: VaultSession): Watchlist? {
        val bytes = session.getVaultRecord(RECORD) ?: return null
        return decodeOrRefuse("watchlist") {
            val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (root["format"]?.jsonPrimitive?.intOrNull != FORMAT) null else json.decodeFromJsonElement(WatchlistRecord.serializer(), root).watchlist
        }
    }
}
