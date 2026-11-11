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
 * One short line for a record: its outcome, then what it did. A signed record also shows its amount. A refusal shows no reason here,
 * because a reason can run long and would be cut mid-sentence; the Activity detail shows the whole reason. A record that named no
 * function reads as "Request", or as "Message" for typed data and messages.
 */
internal fun activitySummary(record: ReviewRecord): String {
    val refused = record.outcome == ReviewOutcome.REFUSED
    val what = record.function ?: when {
        refused -> null
        record.kind == ReviewKind.MESSAGE -> "Message"
        else -> "Request"
    }
    val amount = record.amount.takeIf { record.outcome == ReviewOutcome.SIGNED }
    return listOfNotNull(outcomeLabel(record.outcome), what, amount).joinToString(" · ")
}

/**
 * The catalog sequence in effect, for the Catalog tile. It is the installed package's when that sequence is higher than the build's,
 * as the whitelist rule has it, and otherwise the build's. Null when the sequence is zero, which is the built-in catalog with no
 * sequence of its own.
 */
internal fun effectiveCatalogSequence(bakedSequence: Long, installedSequence: Long?): Long? {
    val inEffect = if (installedSequence != null && installedSequence > bakedSequence) installedSequence else bakedSequence
    return inEffect.takeIf { it > 0 }
}
