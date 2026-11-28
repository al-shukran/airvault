package app.lumio.transactions

import app.lumio.coinNames
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioFormat
import app.lumio.portfolio.SentencePart
import app.lumio.portfolio.SentencePicker
import app.lumio.watchlist.WatchedAsset
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The order of the activity: the newest rows first, or the largest amounts first. */
enum class ActivitySort { NEWEST, LARGEST }

/**
 * The chain family the activity is for, or null for all chains, its order, and the [coin] it is for, or null for every coin. Every account is always
 * shown. A coin names its own chains, so a chain choice does not apply to it.
 */
data class ActivityFilter(val chain: ChainFamily? = null, val sort: ActivitySort = ActivitySort.NEWEST, val coin: WatchedAsset? = null)

/**
 * The filter sentence of the activity: "Showing all chains, all accounts, newest first.", or "Showing ETH, all accounts, newest first." for a coin.
 */
fun ActivityFilter.sentence(): List<SentencePart> = listOf(
    SentencePart.Text("Showing "),
    coin?.let { SentencePart.Text(coinNames(it).first) } ?: SentencePart.Pick(chain?.label ?: "all chains", SentencePicker.CHAIN),
    SentencePart.Text(", all accounts, "),
    SentencePart.Pick(if (sort == ActivitySort.NEWEST) "newest" else "largest", SentencePicker.SORT),
    SentencePart.Text(" first."),
)

/**
 * One row of the activity as it reads: its title, its line under it, its amount, and its value in dollars when a price is known. An other side that no
 * name is known for is its [address], after the [direction] word "From" or "To", on a line of its own.
 */
data class ActivityLine(
    val title: String,
    val detail: String,
    val amount: String,
    /** The value in dollars, null when no price is known. */
    val usd: Double?,
    val incoming: Boolean,
    val pending: Boolean,
    /** The other side's address when no name is known for it, else null. */
    val address: String? = null,
    /** "From" or "To", the word before [address]. */
    val direction: String = if (incoming) "From" else "To",
)

/** The text of the activity rows: what each one is, who it went to or came from, when, and how much. */
object ActivityText {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /**
     * The section a row belongs to, by its day: "Today", "Yesterday", or the day, such as "18 Nov". [nowMs] is the time the list is read at, and
     * [zone] the zone the days are counted in.
     */
    fun section(timestampMs: Long, nowMs: Long, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DAY.format(day)
        }
    }

    /**
     * The line of [tx]. [party] names the other side of a transfer, from the address book or the wallet's own accounts, or null when neither
     * knows it, and the address then goes on a line of its own. [chain] is the network's name and [usd] the value in dollars, or null without a price.
     */
    fun line(tx: LumioTx, chain: String, usd: Double?, party: String?, zone: ZoneId): ActivityLine {
        val incoming = tx.direction == TxDirection.IN
        val title = when {
            incoming -> "Received"
            tx.direction == TxDirection.OUT && isContractCall(tx) -> "Contract Call"
            else -> "Sent"
        }
        val named = party?.takeIf { tx.counterparty != null }
        val time = CLOCK.format(Instant.ofEpochMilli(tx.timestampMs).atZone(zone))
        val pending = tx.status == TxStatus.PENDING
        val direction = when {
            named == null -> null
            incoming -> "From $named"
            else -> "To $named"
        }
        val parts = listOfNotNull(
            if (pending) "Confirming" else null,
            direction,
            chain,
            if (pending) null else time,
        )
        val sign = when {
            tx.amount.signum() == 0 -> ""
            incoming -> "+"
            else -> "−"
        }
        val amount = BigDecimal(tx.amount).movePointLeft(tx.asset.decimals)
        return ActivityLine(
            title = title,
            detail = parts.joinToString(" · "),
            amount = if (amount.signum() == 0) "Fee only" else "$sign${PortfolioFormat.coins(amount)} ${tx.asset.symbol}",
            usd = usd,
            incoming = incoming,
            pending = pending,
            address = tx.counterparty?.takeIf { named == null },
        )
    }

    /** Whether [tx] calls a contract: an outgoing transaction that carries call data. A plain transfer carries none. */
    private fun isContractCall(tx: LumioTx): Boolean = tx.inputData?.let { it.isNotEmpty() && it != "0x" } == true
}
