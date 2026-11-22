package app.lumio.transactions

import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioFormat
import app.lumio.portfolio.SentencePart
import app.lumio.portfolio.SentencePicker
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The order of the activity: the newest rows first, or the largest amounts first. */
enum class ActivitySort { NEWEST, LARGEST }

/** The chain family the activity is for, or null for all chains, and its order. Every account is always shown. */
data class ActivityFilter(val chain: ChainFamily? = null, val sort: ActivitySort = ActivitySort.NEWEST)

/** The filter sentence of the activity: "Showing all chains, all accounts, newest first." */
fun ActivityFilter.sentence(): List<SentencePart> = listOf(
    SentencePart.Text("Showing "),
    SentencePart.Pick(chain?.label ?: "all chains", SentencePicker.CHAIN),
    SentencePart.Text(", all accounts, "),
    SentencePart.Pick(if (sort == ActivitySort.NEWEST) "newest" else "largest", SentencePicker.SORT),
    SentencePart.Text(" first."),
)

/** One row of the activity as it reads: its title, its line under it, its amount, and its value in dollars when a price is known. */
data class ActivityLine(
    val title: String,
    val detail: String,
    val amount: String,
    val fiat: String?,
    val incoming: Boolean,
    val pending: Boolean,
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
     * knows it, and the address is then shortened. [chain] is the network's name and [usd] the value in dollars, or null without a price.
     */
    fun line(tx: LumioTx, chain: String, usd: Double?, party: String?, zone: ZoneId): ActivityLine {
        val incoming = tx.direction == TxDirection.IN
        val title = when {
            incoming -> "Received"
            tx.direction == TxDirection.OUT && isContractCall(tx) -> "Contract Call"
            else -> "Sent"
        }
        val who = (tx.counterparty?.let { party ?: shortAddress(it) })
        val time = CLOCK.format(Instant.ofEpochMilli(tx.timestampMs).atZone(zone))
        val pending = tx.status == TxStatus.PENDING
        val direction = when {
            who == null -> null
            incoming -> "From $who"
            else -> "To $who"
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
            fiat = usd?.let { PortfolioFormat.usd(it) },
            incoming = incoming,
            pending = pending,
        )
    }

    /** Whether [tx] calls a contract: an outgoing transaction that carries call data. A plain transfer carries none. */
    private fun isContractCall(tx: LumioTx): Boolean = tx.inputData?.let { it.isNotEmpty() && it != "0x" } == true

    /** The first six and the last four characters of [address], with an ellipsis between, such as "0x1020…122b". */
    fun shortAddress(address: String): String =
        if (address.length <= SHORT_LIMIT) address else "${address.take(6)}…${address.takeLast(4)}"

    private const val SHORT_LIMIT = 12
}
