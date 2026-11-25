package app.lumio.portfolio

import app.lumio.data.net.NetworkId
import app.lumio.home.HomeAsset
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import java.math.BigDecimal

/**
 * The chain families the balances are filtered by. A family is every network of one kind: [EVM] is Ethereum and its layer 2s, and the Ethereum test
 * networks with them.
 */
enum class ChainFamily(val label: String) {
    EVM("Ethereum"),
    BTC("Bitcoin"),
    SOL("Solana"),
    ALGO("Algorand");

    companion object {
        fun of(network: NetworkId): ChainFamily = when {
            network.isBtc -> BTC
            network.isSol -> SOL
            network.isAlgo -> ALGO
            else -> EVM
        }
    }
}

/**
 * One coin on one network, with its value. [coin] is its symbol and [title] the name the balances show for it. [mergeKey] is shared by the holdings
 * that may add up into one row when the balances are grouped by asset, and it is null for a holding that never adds up: an unofficial, custom or
 * bridged token. [bridged] is true for a bridged or pegged copy, which carries its own tag.
 */
data class PortfolioHolding(
    val coin: String,
    val title: String,
    val network: NetworkId,
    val amount: BigDecimal,
    val usd: Double?,
    val change24h: Double?,
    val mergeKey: String?,
    val bridged: Boolean,
    /** The token's contract, or null for a native coin. A row's logo comes from it. */
    val contract: String? = null,
) {
    val family: ChainFamily get() = ChainFamily.of(network)
}

/** The names the native coins of the balances carry, so that one coin reads the same on every network it is on. */
private val COIN_TITLES = mapOf("ETH" to "Ethereum", "BTC" to "Bitcoin", "SOL" to "Solana", "ALGO" to "Algorand", "MON" to "Monad")

/**
 * The holding of a priced [asset]. [title] names the token, as its registry gives it, and is the coin's name for a native coin. A token is bridged when
 * its symbol or name says it is a bridged or pegged copy, such as "USDC.e". Only a known, official token adds up with the same token on other networks.
 */
fun holdingOf(asset: HomeAsset, title: String? = null): PortfolioHolding = when (asset) {
    is NativeAsset -> PortfolioHolding(
        coin = asset.symbol,
        title = COIN_TITLES[asset.symbol] ?: asset.symbol,
        network = asset.network,
        amount = asset.balance,
        usd = asset.usd,
        change24h = asset.change24h,
        mergeKey = "native:${asset.network.testnet}:${asset.symbol}",
        bridged = false,
    )
    is TokenAsset -> {
        val bridged = isBridged(asset.symbol, title ?: asset.symbol)
        PortfolioHolding(
            coin = asset.symbol,
            title = title ?: asset.symbol,
            network = asset.network,
            amount = asset.balance,
            usd = asset.usd,
            change24h = asset.change24h,
            mergeKey = if (asset.known && !asset.custom && !bridged) "token:${asset.symbol}" else null,
            bridged = bridged,
            contract = asset.contract,
        )
    }
}

/** Whether a token is a bridged or pegged copy of another coin, from the symbol or the name it has. */
internal fun isBridged(symbol: String, name: String): Boolean =
    symbol.contains('.') || name.contains("bridged", ignoreCase = true) || name.contains("pegged", ignoreCase = true)

/** How the balances are shown: the family they are for, whether the same coin adds up across networks, and the order of the rows. */
data class PortfolioFilter(
    val chain: ChainFamily? = null,
    val grouped: Boolean = true,
    val sort: BalanceSort = BalanceSort.BALANCE,
    /** Smallest first for [BalanceSort.BALANCE], and A to Z for [BalanceSort.NAME]. */
    val ascending: Boolean = false,
) {
    /** Grouping applies to all chains only, since one chain has one row for each of its coins. */
    val isGrouped: Boolean get() = grouped && chain == null
}

/** The field the balances are sorted by. */
enum class BalanceSort { BALANCE, NAME }

/** One row of the balances: a coin, or a coin's holdings added up over [networks] when the balances are grouped by asset. */
data class PortfolioRow(
    val coin: String,
    val title: String,
    val amount: BigDecimal,
    val networks: List<NetworkId>,
    val usd: Double?,
    val change24h: Double?,
    val bridged: Boolean,
    /** The contract of the token, or null for a native coin. */
    val contract: String? = null,
) {
    val merged: Boolean get() = networks.size > 1
}

/** The value of every holding: its dollars, and its change over 24 hours in dollars and in percent. Null where no holding has the figure. */
data class PortfolioTotal(val usd: Double?, val changeUsd: Double?, val changePct: Double?)

/** One slice of the allocation line: a coin's [share] of the priced value, from 0 to 1. */
data class AllocationSlice(val label: String, val share: Double)

/** The choice that a part of the filter sentence opens, as a sheet. */
enum class SentencePicker { CHAIN, GROUPING, SORT }

/** A part of a filter sentence: plain text, or a phrase that opens [picker] when tapped. */
sealed interface SentencePart {
    data class Text(val text: String) : SentencePart
    data class Pick(val text: String, val picker: SentencePicker) : SentencePart
}

/** The text of [parts] with its phrases in place, as a reader sees the sentence. */
fun List<SentencePart>.plainText(): String = joinToString("") { part ->
    when (part) {
        is SentencePart.Text -> part.text
        is SentencePart.Pick -> part.text
    }
}

/** The pure rules of the consolidated balances: which holdings make a row, how the rows are sorted, and the totals and allocation of all holdings. */
object PortfolioView {
    /** The rows of [holdings] for [filter], in the filter's order. Holdings are added up by their [PortfolioHolding.mergeKey] when the view is grouped. */
    fun rows(holdings: List<PortfolioHolding>, filter: PortfolioFilter): List<PortfolioRow> {
        val shown = holdings.filter { filter.chain == null || it.family == filter.chain }
        val groups: List<List<PortfolioHolding>> = if (filter.isGrouped) {
            shown.withIndex().groupBy { (index, holding) -> holding.mergeKey ?: "#$index" }.values.map { group -> group.map { it.value } }
        } else {
            shown.map { listOf(it) }
        }
        val rows = groups.map(::rowOf)
        return when (filter.sort) {
            BalanceSort.BALANCE -> rows.sortedWith(
                compareBy<PortfolioRow> { it.usd == null }
                    .thenBy { if (filter.ascending) it.usd ?: 0.0 else -(it.usd ?: 0.0) }
                    .thenBy { it.title },
            )
            BalanceSort.NAME -> rows.sortedWith(
                compareBy<PortfolioRow> { it.title.lowercase() }.thenBy { it.coin }.let { if (filter.ascending) it else it.reversed() },
            )
        }
    }

    /** The value of all [holdings]. A change is counted only for the holdings that have both a price and a change. */
    fun total(holdings: List<PortfolioHolding>): PortfolioTotal {
        val usd = holdings.mapNotNull { it.usd }.takeIf { it.isNotEmpty() }?.sum()
        val changed = holdings.filter { it.usd != null && it.change24h != null && 1 + it.change24h / 100 > 0 }
        if (changed.isEmpty()) return PortfolioTotal(usd, null, null)
        val now = changed.sumOf { it.usd!! }
        val before = changed.sumOf { it.usd!! / (1 + it.change24h!! / 100) }
        val change = now - before
        return PortfolioTotal(usd, change, if (before > 0) change / before * 100 else null)
    }

    /**
     * Whether the side has nothing to show: no holding has a value, and none holds any coins. Funds with no price are not nothing: a testnet coin never
     * has a price, and a mainnet coin has none until a source answers.
     */
    fun isEmpty(holdings: List<PortfolioHolding>): Boolean = total(holdings).usd == null && holdings.none { it.amount.signum() > 0 }

    /**
     * The shares of the three coins with the most value, then "Other" for the rest when there is any. Each coin's value is all its holdings, on every
     * network. Empty when no holding has a price.
     */
    fun allocation(holdings: List<PortfolioHolding>): List<AllocationSlice> {
        val priced = holdings.filter { it.usd != null }
        val total = priced.sumOf { it.usd!! }
        if (total <= 0.0) return emptyList()
        val byCoin = priced.groupBy { it.coin }.map { (coin, items) -> coin to items.sumOf { it.usd!! } }.sortedByDescending { it.second }
        val top = byCoin.take(TOP_COINS).map { (coin, usd) -> AllocationSlice(coin, usd / total) }
        val rest = byCoin.drop(TOP_COINS).sumOf { it.second }
        return if (rest > 0) top + AllocationSlice("Other", rest / total) else top
    }

    /** One row from the holdings it adds up. The change is weighted by each holding's value before the change. */
    private fun rowOf(items: List<PortfolioHolding>): PortfolioRow {
        val first = items.first()
        val priced = items.filter { it.usd != null }
        return PortfolioRow(
            coin = first.coin,
            title = first.title,
            amount = items.fold(BigDecimal.ZERO) { sum, holding -> sum + holding.amount },
            networks = items.map { it.network }.distinct(),
            usd = priced.takeIf { it.isNotEmpty() }?.sumOf { it.usd!! },
            change24h = changeOf(priced),
            bridged = first.bridged,
            contract = first.contract,
        )
    }

    /** The change in percent of [items], each with a value and a change: their value now against their value before the change. */
    private fun changeOf(items: List<PortfolioHolding>): Double? {
        val changed = items.filter { it.change24h != null && 1 + it.change24h / 100 > 0 }
        if (changed.isEmpty()) return null
        val now = changed.sumOf { it.usd!! }
        val before = changed.sumOf { it.usd!! / (1 + it.change24h!! / 100) }
        return if (before > 0) (now / before - 1) * 100 else null
    }

    private const val TOP_COINS = 3
}

/** The filter sentence of the balances, with each phrase that opens a picker marked. */
fun PortfolioFilter.sentence(): List<SentencePart> {
    val family = chain
    if (family != null) {
        return listOf(
            SentencePart.Text("Showing "),
            SentencePart.Pick(family.label, SentencePicker.CHAIN),
            SentencePart.Text(" only (grouping is off for a single chain)."),
        )
    }
    val sortParts = when (sort) {
        BalanceSort.BALANCE -> listOf(
            SentencePart.Text(if (ascending) "smallest " else "largest "),
            SentencePart.Pick("balance", SentencePicker.SORT),
            SentencePart.Text(" first"),
        )
        BalanceSort.NAME -> listOf(
            SentencePart.Text("sorted by "),
            SentencePart.Pick("name", SentencePicker.SORT),
            SentencePart.Text(if (ascending) ", A to Z" else ", Z to A"),
        )
    }
    return listOf(
        SentencePart.Text("Showing "),
        SentencePart.Pick("all chains", SentencePicker.CHAIN),
        SentencePart.Text(", "),
        SentencePart.Pick(if (grouped) "grouped by asset" else "not grouped", SentencePicker.GROUPING),
        SentencePart.Text(", "),
    ) + sortParts + SentencePart.Text(".")
}
