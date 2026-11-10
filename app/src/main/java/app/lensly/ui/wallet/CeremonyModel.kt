package app.lensly.ui.wallet

/** How the owner adds their own randomness to the device's committed randomness. */
enum class EntropyMode(
    /** Every symbol the entry pad records in this mode. */
    val symbols: String,
) {
    Dice(symbols = "123456"),
    Coins(symbols = "HT");

    /** True when [symbol] is a face this mode can record. */
    fun accepts(symbol: Char): Boolean = symbols.indexOf(symbol) >= 0
}

/**
 * Upper-cases [hex], splits it into groups of four, and joins [groupsPerLine] groups per line. A
 * 64-character hash gives four lines with four groups each.
 */
fun formatHexLines(hex: String, groupsPerLine: Int = 4): List<String> {
    require(groupsPerLine > 0) { "groupsPerLine must be positive" }
    return hex.uppercase().chunked(4).chunked(groupsPerLine) { line -> line.joinToString(" ") }
}

/** The first [groups] groups of [hex] and an ellipsis, e.g. "A1B2 C3D4 E5F6 7890…". */
fun previewHex(hex: String, groups: Int = 4): String =
    hex.uppercase().chunked(4).take(groups).joinToString(" ") + "…"
