package app.lumio.watchlist

import app.lensly.core.vault.VaultSession
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The order of a watchlist: by market cap (the default), or by the 24 h change with the biggest gain first. */
enum class WatchlistSort(val label: String) {
    MARKET_CAP("Market Cap"),
    CHANGE_24H("24h Change"),
}

/** One watched asset as the vault keeps it: its network, and its lowercase contract for a token. A null contract is the network's native coin. */
@Serializable
data class WatchedAsset(val network: NetworkId, val contract: String? = null) {
    val assetId: AssetId get() = contract?.let { AssetId.Erc20(network, it) } ?: AssetId.Native(network)
}

/** One wallet's watchlist: its assets in the order they were starred, and the order the list shows them in. */
@Serializable
data class WalletWatchlist(val assets: List<WatchedAsset> = emptyList(), val sort: WatchlistSort = WatchlistSort.MARKET_CAP)

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

/** The watchlist with [asset] starred, or without it when it is starred already. The other assets keep the order they were starred in. */
fun WalletWatchlist.toggled(asset: WatchedAsset): WalletWatchlist =
    copy(assets = if (asset in assets) assets - asset else assets + asset)

/** The watchlists of every wallet, as the one vault record that holds them. */
@Serializable
private data class WatchlistRecord(val wallets: Map<String, WalletWatchlist> = emptyMap())

/** Reads and writes the watchlists as the vault record [RECORD], which is encrypted under the vault key like the other settings records. */
object WatchlistStore {
    /** The vault record that holds the watchlists of all wallets. */
    const val RECORD = "lumio-watchlist"

    private val json = Json

    /** The watchlist of [walletId] as the vault stores it, or null when the wallet has none yet, so it shows [FIRST_RUN_ASSETS]. */
    fun read(session: VaultSession, walletId: String): WalletWatchlist? = readAll(session)[walletId]

    /** Stores [watchlist] for [walletId] and keeps the lists of the other wallets. */
    fun write(session: VaultSession, walletId: String, watchlist: WalletWatchlist) = saveAll(session, readAll(session) + (walletId to watchlist))

    /** Deletes the watchlist of [walletId] and keeps the lists of the other wallets. */
    fun remove(session: VaultSession, walletId: String) = saveAll(session, readAll(session) - walletId)

    private fun saveAll(session: VaultSession, wallets: Map<String, WalletWatchlist>) {
        session.putVaultRecord(RECORD, json.encodeToString(WatchlistRecord.serializer(), WatchlistRecord(wallets)).encodeToByteArray())
    }

    private fun readAll(session: VaultSession): Map<String, WalletWatchlist> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyMap()
        return try {
            json.decodeFromString(WatchlistRecord.serializer(), bytes.decodeToString()).wallets
        } catch (e: SerializationException) {
            emptyMap()
        }
    }
}
