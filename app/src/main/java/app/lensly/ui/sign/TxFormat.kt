package app.lensly.ui.sign

/**
 * Splits an address into 4-character groups separated by spaces. The full address is kept. Text
 * wraps only at the spaces, so no group is cut in half.
 */
internal fun groupAddress(address: String): String = address.chunked(4).joinToString(" ")

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** True for a 0x-prefixed address of 40 hexadecimal digits, in either letter case. */
internal fun isEvmAddress(text: String): Boolean = EVM_ADDRESS.matches(text)
