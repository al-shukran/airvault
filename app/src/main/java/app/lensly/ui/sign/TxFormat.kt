package app.lensly.ui.sign

/**
 * Splits hexadecimal [hex] (no 0x prefix) into groups of [size] digits separated by spaces. Every digit is kept, and text
 * wraps only at the spaces.
 */
internal fun groupHex(hex: String, size: Int = 8): String = hex.chunked(size).joinToString(" ")
