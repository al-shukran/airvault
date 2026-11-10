package app.lensly.core.wallet

/** Lowercase hex without a 0x prefix. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
