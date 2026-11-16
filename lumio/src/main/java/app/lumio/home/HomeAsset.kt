package app.lumio.home

import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.price.Price
import java.math.BigDecimal
import java.util.Locale

/**
 * An asset that Home lists: one coin on one network, the native coin or an ERC-20 token. [id] names it for as long as a list shows it, and it is the
 * key of the asset page. [symbol] is the coin and [name] the network it is on. [balance] is the amount in whole coins. [usd] is the value in dollars,
 * null when no price is known, and [change24h] the change over 24 hours in percent, null when no source gives one. [testnet] is true on a testnet,
 * which never has a price. [assetId] is what the price is of.
 */
sealed interface HomeAsset {
    val id: String
    val symbol: String
    val name: String
    val network: NetworkId
    val balance: BigDecimal
    val usd: Double?
    val change24h: Double?
    val testnet: Boolean
    val assetId: AssetId
}

/** The native coin of one network, summed over the paired accounts that hold it. Its price is applied by [HomeAssets.priced]. */
data class NativeAsset(
    override val id: String,
    override val symbol: String,
    override val name: String,
    override val network: NetworkId,
    override val balance: BigDecimal,
    override val usd: Double? = null,
    override val change24h: Double? = null,
) : HomeAsset {
    override val testnet: Boolean get() = network.testnet
    override val assetId: AssetId get() = AssetId.Native(network)

    companion object {
        /** The id of the native coin of [network]: its family and chain, such as "EVM-1" for Ethereum or "BTC-mainnet" for Bitcoin. */
        fun idOf(network: NetworkId): String = "${network.family.name}-${network.chain}"
    }
}

/**
 * An ERC-20 token on one EVM network, summed over the wallets and addresses that hold it. [contract] is its lowercase contract address, and [name] is
 * the network's name, as it is for a native coin. Its price is applied by [HomeAssets.priced] from its contract. [known] is false for a token that neither the
 * registry nor the bundled default list names: the owner added it, or someone else sent it. [custom] is true for a token the owner added to the list.
 */
data class TokenAsset(
    override val id: String,
    override val symbol: String,
    override val name: String,
    override val network: NetworkId,
    val contract: String,
    override val balance: BigDecimal,
    override val usd: Double? = null,
    override val change24h: Double? = null,
    val known: Boolean = true,
    val custom: Boolean = false,
) : HomeAsset {
    override val testnet: Boolean get() = network.testnet
    override val assetId: AssetId get() = AssetId.Erc20(network, contract)

    companion object {
        /** The id of [contract] on [network], such as "EVM-1-0xa0b8...". */
        fun idOf(network: NetworkId, contract: String): String = "${network.family.name}-${network.chain}-$contract"
    }
}

/** The rules of the balances' values: the total, and how the prices meet the balances. */
object HomeAssets {
    /** The total in dollars of the priced [assets], or null when none has a price, since an unknown total is not zero. */
    fun totalUsd(assets: List<HomeAsset>): Double? = assets.mapNotNull { it.usd }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * [assets] with [prices] applied, each priced by its [HomeAsset.assetId]. The value in dollars is the balance times the price, and the 24-hour
     * change is the price's. An asset with no price keeps neither.
     */
    fun priced(assets: List<HomeAsset>, prices: Map<AssetId, Price>): List<HomeAsset> = assets.map { asset ->
        val price = prices[asset.assetId]
        val usd = price?.let { asset.balance.toDouble() * it.usd }
        val change = price?.change24hPct
        when (asset) {
            is NativeAsset -> asset.copy(usd = usd, change24h = change)
            is TokenAsset -> asset.copy(usd = usd, change24h = change)
        }
    }

    /**
     * The time of the oldest kept price among the prices of [assets], or null when none of them is kept from an earlier read. Wallet screens show it as
     * the time the prices are from.
     */
    fun staleSinceMs(assets: List<HomeAsset>, prices: Map<AssetId, Price>): Long? =
        assets.mapNotNull { prices[it.assetId] }.filter { it.stale }.minOfOrNull { it.asOfMs }
}

/** What a hidden amount shows in place of its digits. */
object Masking {
    /** The mask of a hidden amount. It is the same for every amount, so it gives nothing away. */
    const val MASK = "••••••"

    /** [text] when it is shown, or the mask when it is [hidden]. */
    fun amount(text: String, hidden: Boolean): String = if (hidden) MASK else text
}

/** The text of Home's numbers. It is the same in every locale, so the goldens and the tests read the same everywhere. */
object HomeFormat {
    /** A dollar amount with two decimals and grouping commas, such as "$1,234.56". */
    fun usd(value: Double): String = String.format(Locale.ROOT, "\$%,.2f", value)

    /** A balance in whole coins with its trailing zeros dropped, such as "0.5" or "0". */
    fun coins(balance: BigDecimal): String = balance.stripTrailingZeros().toPlainString()

    /** A price per coin: two decimals from one dollar up, and six below it, so a small price keeps its digits, such as "$0.000123". */
    fun price(value: Double): String = if (value >= 1.0) usd(value) else String.format(Locale.ROOT, "\$%.6f", value)

    /** A change over 24 hours in percent with its sign, such as "+1.25%" or "-0.40%". */
    fun change(percent: Double): String = String.format(Locale.ROOT, "%+.2f%%", percent)
}
