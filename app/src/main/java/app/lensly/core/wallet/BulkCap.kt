package app.lensly.core.wallet

/**
 * The Bulk Cap: the most accounts one Bulk Run may create. The owner selects it in the Vault Area. A vault that has
 * never saved a choice uses [DEFAULT]. There is no uncapped option.
 *
 * Chunked mode (100 accounts per chunk, resumable) is not built yet. So 1,000 is the largest cap, and a run of that
 * size is saved in one atomic step.
 */
enum class BulkCap(val accounts: Int) {
    HUNDRED(100),
    THOUSAND(1_000);

    /** The cap as the screens show it, such as "1,000". */
    val label: String get() = formatAccounts(accounts)

    companion object {
        val DEFAULT: BulkCap = HUNDRED

        /** The cap that allows [accounts] accounts, or null when no cap has that size. */
        fun fromAccounts(accounts: Int): BulkCap? = entries.firstOrNull { it.accounts == accounts }
    }
}

/** A count with thousands separators, such as "1,000". */
fun formatAccounts(count: Int): String = count.toString().reversed().chunked(3).joinToString(",").reversed()
