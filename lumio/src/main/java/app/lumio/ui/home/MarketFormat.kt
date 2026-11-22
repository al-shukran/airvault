package app.lumio.ui.home

import java.text.NumberFormat
import java.util.Locale

/** Dollars to the cent, as the watchlist and the trending rows write a price. */
internal val FIAT: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

private const val BILLION = 1e9
private const val TRILLION = 1e12

/** A dollar total as the hero writes it: trillions or billions to two places, such as "\$3.41 T", and a smaller total in full dollars. */
internal fun compactUsd(value: Double): String = when {
    value >= TRILLION -> String.format(Locale.US, "\$%.2f T", value / TRILLION)
    value >= BILLION -> String.format(Locale.US, "\$%.2f B", value / BILLION)
    else -> FIAT.format(value)
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
