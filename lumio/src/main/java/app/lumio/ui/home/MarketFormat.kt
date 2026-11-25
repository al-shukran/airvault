package app.lumio.ui.home

import app.lumio.data.config.Fiat
import java.util.Locale

private const val BILLION = 1e9
private const val TRILLION = 1e12

/**
 * A dollar total as the hero writes it, in the owner's currency: trillions or billions to two places, such as "$3.41T", and a smaller total in full.
 */
internal fun compactUsd(usd: Double, fiat: Fiat): String {
    val value = fiat.convert(usd)
    return when {
        value >= TRILLION -> String.format(Locale.ROOT, "%s%.2fT", fiat.currency.prefix, value / TRILLION)
        value >= BILLION -> String.format(Locale.ROOT, "%s%.2fB", fiat.currency.prefix, value / BILLION)
        else -> fiat.amount(usd)
    }
}

/** How long ago, as the freshness line writes it: "8 s ago", "3 min ago", "3 h ago" or "2 d ago". A time in the future reads as just now. */
internal fun agoText(ms: Long): String {
    val seconds = (ms / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "$seconds s ago"
        seconds < 3_600L -> "${seconds / 60L} min ago"
        seconds < 86_400L -> "${seconds / 3_600L} h ago"
        else -> "${seconds / 86_400L} d ago"
    }
}

/** The freshness line under the hero: "Live · updated 8 s ago" for a figure a source answered now, else "Last known · updated 3 h ago". */
internal fun freshnessOf(asOfMs: Long, stale: Boolean, nowMs: Long): String {
    val ago = agoText(nowMs - asOfMs)
    return if (stale) "Last known · updated $ago" else "Live · updated $ago"
}
