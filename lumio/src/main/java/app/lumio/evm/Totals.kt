package app.lumio.evm

import java.math.BigInteger

/** The sum of one native asset over the chains of one side: its [symbol], its [decimals], the [wei] total, and the [networks] it spans. */
data class Total(
    val symbol: String,
    val decimals: Int,
    val wei: BigInteger,
    val networks: Int,
    val testnet: Boolean,
)

/**
 * The totals of the holdings, in two lists. A testnet balance is never added to a mainnet balance: the lists are built apart, and a total
 * is keyed by its side, so Sepolia ETH and Ethereum ETH stay two totals. [oldestMs] is the time of the oldest read in the holdings, or null.
 */
data class Totals(
    val mainnet: List<Total>,
    val testnet: List<Total>,
    val oldestMs: Long?,
)

object PortfolioTotals {
    /** The totals of [holdings], grouped by side, symbol and decimals. */
    fun of(holdings: List<Holding>): Totals {
        val totals = holdings
            .groupBy { Side(it.chain.testnet, it.chain.symbol, it.chain.decimals) }
            .map { (side, items) ->
                Total(
                    symbol = side.symbol,
                    decimals = side.decimals,
                    wei = items.fold(BigInteger.ZERO) { sum, holding -> sum + holding.wei },
                    networks = items.map { it.chain.chainId }.distinct().size,
                    testnet = side.testnet,
                )
            }
            .sortedWith(compareBy<Total>({ it.symbol }, { it.decimals }))
        return Totals(
            mainnet = totals.filter { !it.testnet },
            testnet = totals.filter { it.testnet },
            oldestMs = holdings.minOfOrNull { it.atMs },
        )
    }

    private data class Side(val testnet: Boolean, val symbol: String, val decimals: Int)
}
