package app.lensly.ui.home

import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/** The line under Home's aperture, such as "Sealed · 5 wallets · 19 accounts". It counts only the wallets of this session. */
internal fun sealedLine(wallets: Int, accounts: Int): String =
    "Sealed · ${counted(wallets, "wallet")} · ${counted(accounts, "account")}"

private fun counted(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * How long ago a record was made, read against [nowMs]: "Just now", "12 min ago", "3 h ago", "Yesterday", and a date for anything
 * older. The date is read in the device's zone, as the History screens read theirs.
 */
internal fun relativeAge(atMs: Long, nowMs: Long): String {
    val age = nowMs - atMs
    return when {
        age < MINUTE_MS -> "Just now"
        age < HOUR_MS -> "${age / MINUTE_MS} min ago"
        age < DAY_MS -> "${age / HOUR_MS} h ago"
        age < 2 * DAY_MS -> "Yesterday"
        else -> Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

/**
 * What a record says in one line: the function it reviewed, then its amount. A refusal names its reason instead. A record that
 * named neither reads as "Request", or "Message" for typed data and messages.
 */
internal fun activitySummary(record: ReviewRecord): String {
    val refused = record.outcome == ReviewOutcome.REFUSED
    val what = (if (refused) record.refusal ?: record.function else record.function ?: record.refusal)
        ?: if (record.kind == ReviewKind.MESSAGE) "Message" else "Request"
    return if (refused) what else listOfNotNull(what, record.amount).joinToString(" · ")
}
