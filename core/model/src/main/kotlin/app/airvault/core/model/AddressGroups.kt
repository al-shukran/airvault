package app.airvault.core.model

/** How an address is written in text: in groups of 4, or cut to the groups at its two ends. One rule for every screen, sentence and step. */
object AddressGroups {
    private const val GROUP = 4
    private const val ENDS_GROUPS = 2
    private const val SHORT_HEAD = 6
    private const val SHORT_TAIL = 4

    /**
     * [address] in groups of four characters separated by spaces. A `0x` prefix stays in front of the first group and is not counted in
     * the groups, so an EVM address reads `0x1a2B 3c4D …`.
     */
    fun group(address: String): String {
        val prefix = if (address.startsWith("0x")) "0x" else ""
        return prefix + address.removePrefix(prefix).chunked(GROUP).joinToString(" ")
    }

    /**
     * [address] in groups of 4, cut to its first two and its last two groups around an ellipsis, as "0x1234 5678 … 9abc def0", and in full
     * when it has no more than four groups.
     */
    fun ends(address: String): String {
        val groups = group(address).split(" ")
        return if (groups.size <= ENDS_GROUPS * 2) groups.joinToString(" ") else (groups.take(ENDS_GROUPS) + "…" + groups.takeLast(ENDS_GROUPS)).joinToString(" ")
    }

    /**
     * [address] for a row with little room. An EVM `0x` address is written as [ends]. Any other encoding (base58, bech32) is not grouped and
     * keeps its first six and last four characters around an ellipsis, as "bc1qar…5mdq", and in full when it is no longer than that.
     */
    fun short(address: String): String = when {
        address.startsWith("0x") -> ends(address)
        address.length <= SHORT_HEAD + SHORT_TAIL -> address
        else -> address.take(SHORT_HEAD) + "…" + address.takeLast(SHORT_TAIL)
    }
}
