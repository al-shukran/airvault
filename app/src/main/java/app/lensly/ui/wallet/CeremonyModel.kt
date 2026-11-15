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

/** The first [groups] groups of [hex] and an ellipsis, e.g. "A1B2 C3D4 E5F6 7890…". */
fun previewHex(hex: String, groups: Int = 4): String =
    hex.uppercase().chunked(4).take(groups).joinToString(" ") + "…"
