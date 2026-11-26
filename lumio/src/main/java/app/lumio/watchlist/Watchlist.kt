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
 * One wallet's watchlist: its assets in the order they were starred, the order the list shows them in, and the [labels] kept with the assets starred
 * from Add to Watchlist, by [WatchedAsset.key].
 */
@Serializable
data class WalletWatchlist(
    val assets: List<WatchedAsset> = emptyList(),
    val sort: WatchlistSort = WatchlistSort.MARKET_CAP,
    val labels: Map<String, WatchedLabel> = emptyMap(),
)

/** The assets a wallet's watchlist starts with, before the owner stars any: the mainnet coins that are most watched. */
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
fun WalletWatchlist.ordered(change24h: (WatchedAsset) -> Double?): List<WatchedAsset> = when (sort) {
    WatchlistSort.MARKET_CAP -> assets
    WatchlistSort.CHANGE_24H -> assets.sortedByDescending(change24h)
}

/**
 * The watchlist with [asset] starred, with its [label] when one is given, or without it and its label when it is starred already. The other assets keep
 * the order they were starred in.
 */
fun WalletWatchlist.toggled(asset: WatchedAsset, label: WatchedLabel? = null): WalletWatchlist = if (asset in assets) {
    copy(assets = assets - asset, labels = labels - asset.key)
} else {
    copy(assets = assets + asset, labels = if (label == null) labels else labels + (asset.key to label))
}

/** The watchlists of every wallet, as the one vault record that holds them. */
@Serializable
private data class WatchlistRecord(val format: Int, val wallets: Map<String, WalletWatchlist> = emptyMap())

/** Reads and writes the watchlists as the vault record [RECORD], which is encrypted under the vault key like the other settings records. */
object WatchlistStore {
    /** The vault record that holds the watchlists of all wallets. */
    const val RECORD = "lumio-watchlist"

    /**
     * The format of the record: 2 keeps the [WatchedLabel]s with the assets. A record of an earlier format is not read, so its wallets start again from
     * [FIRST_RUN_ASSETS], and the next change replaces it.
     */
    const val FORMAT = 2

    private val json = Json

    /** The watchlist of [walletId] as the vault stores it, or null when the wallet has none yet, so it shows [FIRST_RUN_ASSETS]. */
    fun read(session: VaultSession, walletId: String): WalletWatchlist? = readAll(session)[walletId]

    /**
     * Stores [watchlist] for [walletId] and keeps the lists of the other wallets. A stored record that does not parse is left as it is, and this throws
     * [UnreadableRecordException].
     */
    fun write(session: VaultSession, walletId: String, watchlist: WalletWatchlist) = saveAll(session, load(session) + (walletId to watchlist))

    /**
     * Deletes the watchlist of [walletId] and keeps the lists of the other wallets. A stored record that does not parse is left as it is, and this throws
     * [UnreadableRecordException].
     */
    fun remove(session: VaultSession, walletId: String) = saveAll(session, load(session) - walletId)

    private fun saveAll(session: VaultSession, wallets: Map<String, WalletWatchlist>) {
        session.putVaultRecord(RECORD, json.encodeToString(WatchlistRecord.serializer(), WatchlistRecord(FORMAT, wallets)).encodeToByteArray())
    }

    /** The watchlists of every wallet that has one, by wallet id, from one read of the record. */
    fun readAll(session: VaultSession): Map<String, WalletWatchlist> = try {
        load(session)
    } catch (e: UnreadableRecordException) {
        emptyMap()
    }

    /**
     * The watchlists of every wallet that has one. Empty when none is stored, or the record is of an earlier [FORMAT]. @throws UnreadableRecordException
     * when the record does not parse.
     */
    private fun load(session: VaultSession): Map<String, WalletWatchlist> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyMap()
        return decodeOrRefuse("watchlists") {
            val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (root["format"]?.jsonPrimitive?.intOrNull != FORMAT) emptyMap() else json.decodeFromJsonElement(WatchlistRecord.serializer(), root).wallets
        }
    }
}
