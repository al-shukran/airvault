package app.lensly.app

/** One section of a wallet's account list: a group's name and its accounts, or no name for the accounts in no group. */
data class AccountSection(val group: String?, val indices: List<Int>)

/**
 * The accounts [indices] in the order the detail screen shows them. Accounts in a group come under that group's name. The
 * groups are in name order, ignoring case, and the accounts in no group come last, with no name. Within a section the accounts
 * are in index order, and a duplicate index counts once. When no account has a group, there is one section with no name, so
 * the list has no headers.
 *
 * Group names are compared as they are stored, so "Work" and "work" are two groups.
 */
fun accountSections(indices: Collection<Int>, groupOf: (Int) -> String?): List<AccountSection> {
    val ordered = indices.distinct().sorted()
    if (ordered.isEmpty()) return emptyList()
    val ungrouped = ordered.filter { groupOf(it) == null }
    val byGroup = ordered
        .mapNotNull { index -> groupOf(index)?.let { group -> group to index } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    if (byGroup.isEmpty()) return listOf(AccountSection(group = null, indices = ordered))
    val groups = byGroup.keys
        .sortedWith(String.CASE_INSENSITIVE_ORDER.thenComparator { a, b -> a.compareTo(b) })
        .map { name -> AccountSection(group = name, indices = byGroup.getValue(name)) }
    return if (ungrouped.isEmpty()) groups else groups + AccountSection(group = null, indices = ungrouped)
}
