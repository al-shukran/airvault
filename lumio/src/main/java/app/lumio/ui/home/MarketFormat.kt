package app.lumio.ui.home

/** How long ago, as a freshness line writes it: "8 s ago", "3 min ago", "3 h ago" or "2 d ago". A time in the future reads as just now. */
internal fun agoText(ms: Long): String {
    val seconds = (ms / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "$seconds s ago"
        seconds < 3_600L -> "${seconds / 60L} min ago"
        seconds < 86_400L -> "${seconds / 3_600L} h ago"
        else -> "${seconds / 86_400L} d ago"
    }
}
