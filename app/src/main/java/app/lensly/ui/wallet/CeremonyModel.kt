package app.lensly.ui.wallet

/** Dice rolls the owner enters in a dice ceremony. Each roll gives about 2.6 bits. */
const val DICE_ROLL_COUNT = 50

/** Coin flips the owner enters in a coin ceremony. Each flip gives exactly one bit. */
const val COIN_FLIP_COUNT = 128

/** How the owner adds their own randomness to the device's committed randomness. */
enum class EntropyMode(
    /** Every symbol the entry pad records in this mode. */
    val symbols: String,
    /** Number of symbols the ceremony asks for by default. */
    val defaultCount: Int,
) {
    Dice(symbols = "123456", defaultCount = DICE_ROLL_COUNT),
    Coins(symbols = "HT", defaultCount = COIN_FLIP_COUNT);

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
