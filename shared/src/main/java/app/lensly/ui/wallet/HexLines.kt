package app.lensly.ui.wallet

/**
 * Upper-cases [hex], splits it into groups of four, and joins [groupsPerLine] groups per line. A
 * 64-character hash gives four lines with four groups each.
 */
fun formatHexLines(hex: String, groupsPerLine: Int = 4): List<String> {
    require(groupsPerLine > 0) { "groupsPerLine must be positive" }
    return hex.uppercase().chunked(4).chunked(groupsPerLine) { line -> line.joinToString(" ") }
}
