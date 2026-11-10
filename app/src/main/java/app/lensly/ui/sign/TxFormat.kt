package app.lensly.ui.sign

/**
 * Splits an address into 4-character groups separated by spaces. The full address is kept. Text
 * wraps only at the spaces, so no group is cut in half.
 */
internal fun groupAddress(address: String): String = address.chunked(4).joinToString(" ")
