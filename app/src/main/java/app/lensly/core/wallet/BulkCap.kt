package app.lensly.core.wallet

/**
 * The Bulk Cap: the most accounts one Bulk Run may create. The owner selects it in Settings. A vault that has never
 * saved a choice uses [DEFAULT]. There is no uncapped option.
 */
enum class BulkCap(val accounts: Int) {
    TWENTY(20),
    FIFTY(50),
    HUNDRED(100);

    /** The cap as the screens show it, such as "100". */
    val label: String get() = formatAccounts(accounts)

    companion object {
        val DEFAULT: BulkCap = FIFTY

        /** The cap that allows [accounts] accounts, or null when no cap has that size. */
        fun fromAccounts(accounts: Int): BulkCap? = entries.firstOrNull { it.accounts == accounts }
    }
}

/** A count with thousands separators, such as "1,000". */
fun formatAccounts(count: Int): String = count.toString().reversed().chunked(3).joinToString(",").reversed()
