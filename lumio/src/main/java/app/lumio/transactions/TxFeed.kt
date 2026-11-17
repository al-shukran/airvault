package app.lumio.transactions

import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAssetRef
import app.lumio.data.tx.TxFilter
import app.lumio.data.tx.TxQuery
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettingsStore
import app.lumio.home.HomeAsset
import app.lumio.home.TokenAsset
import java.math.BigDecimal
import java.time.ZoneId

/** One row of the feed: the transaction, its value in dollars when a price is known, its comment, and the chain it is on. */
data class TxFeedItem(val tx: LumioTx, val usd: Double? = null, val comment: String? = null, val chain: String = "")

/** A page of the feed, with the number of transactions that the filter matches in all. */
data class TxPage(val items: List<TxFeedItem>, val count: Int) {
    companion object {
        val EMPTY = TxPage(emptyList(), 0)
    }
}

/** An asset that the filter can pick: its reference, its symbol, and the chain it is on. */
data class TxAssetOption(val ref: TxAssetRef, val symbol: String, val chain: String)

/** The EVM network of [chain], as the store names it. */
fun evmNetwork(chain: EvmChain): NetworkId = NetworkId(Family.EVM, chain.chainId.toString(), chain.testnet)

/** The price key of the asset that [tx] moved: its token, or the native coin of its network. */
fun assetIdOf(tx: LumioTx): AssetId = tx.asset.contract?.let { AssetId.Erc20(tx.network, it) } ?: AssetId.Native(tx.network)

/** The name of the chain that [network] is, as the feed shows it. */
internal fun chainLabel(network: NetworkId, evm: Map<NetworkId, EvmChain>): String = when (network.family) {
    Family.BTC -> if (network.testnet) "Bitcoin testnet4" else "Bitcoin"
    Family.EVM -> evm[network]?.name ?: "Chain ${network.chain}"
    Family.SOL, Family.ALGO -> network.chain
}

/** The chains of [chains] keyed by their network, so that a row finds the name of its chain. */
internal fun evmChainsByNetwork(chains: List<EvmChain>): Map<NetworkId, EvmChain> = chains.associateBy { evmNetwork(it) }

/** The chains of the open vault, the registry's and the owner's own, keyed by network. */
internal fun vaultChains(app: LumioApp, vault: LumioVault): Map<NetworkId, EvmChain> =
    evmChainsByNetwork(EvmCatalog.chains(app.registry, EvmSettingsStore.read(vault.session)))

/**
 * The page of the feed that [filter] shows on [plan], read from the vault's store, without prices. Each row carries its comment and its chain. [limit]
 * is how many rows the page holds, and [hideSpam] leaves the spam out, as the Settings say.
 */
internal suspend fun txPage(app: LumioApp, vault: LumioVault, plan: TxSyncPlan, filter: TxFilter, limit: Int, hideSpam: Boolean): TxPage {
    val store = vault.txs
    val query = filter.toQuery(plan.networks, System.currentTimeMillis(), ZoneId.systemDefault(), includeSpam = !hideSpam)
    val chains = vaultChains(app, vault)
    val notes = store.comments().associate { it.txId to it.text }
    val items = store.page(query, 0, limit).map { tx ->
        TxFeedItem(tx = tx, comment = notes[tx.id], chain = chainLabel(tx.network, chains))
    }
    return TxPage(items = items, count = store.count(query))
}

/**
 * The newest [limit] rows of the accounts [addresses] on [networks], from the vault's store, each with its comment and its chain. Spam is left out, and no
 * row carries a value: an account's activity shows the rows as the store holds them.
 */
internal suspend fun accountPage(app: LumioApp, vault: LumioVault, addresses: Set<String>, networks: Set<NetworkId>, limit: Int): TxPage {
    if (addresses.isEmpty() || networks.isEmpty()) return TxPage.EMPTY
    val store = vault.txs
    val query = TxQuery(accounts = addresses, networks = networks)
    val chains = vaultChains(app, vault)
    val notes = store.comments().associate { it.txId to it.text }
    val items = store.page(query, 0, limit).map { tx ->
        TxFeedItem(tx = tx, comment = notes[tx.id], chain = chainLabel(tx.network, chains))
    }
    return TxPage(items = items, count = store.count(query))
}

/** [page] with the value in dollars of each row, from the prices of its assets. A row whose asset has no price keeps none. */
internal suspend fun pricedPage(app: LumioApp, page: TxPage): TxPage {
    if (page.items.isEmpty()) return page
    val prices = app.prices.assetPrices(page.items.map { assetIdOf(it.tx) }.distinct())
    return page.copy(
        items = page.items.map { item ->
            val price = prices[assetIdOf(item.tx)]
            item.copy(usd = price?.let { BigDecimal(item.tx.amount).movePointLeft(item.tx.asset.decimals).toDouble() * it.usd })
        },
    )
}

/** The assets that [assets] holds, as the filter picks them: each coin or token once, by its reference. */
internal fun assetOptions(assets: List<HomeAsset>): List<TxAssetOption> = assets
    .map { asset ->
        val contract = (asset as? TokenAsset)?.contract?.lowercase()
        TxAssetOption(ref = TxAssetRef(asset.network, contract), symbol = asset.symbol, chain = asset.name)
    }
    .distinctBy { it.ref }
