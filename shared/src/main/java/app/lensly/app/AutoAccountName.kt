package app.lensly.app

/**
 * The Auto-Name of account [index] of a wallet whose name at creation was [baseName]: that name, a space, and the index
 * counted from 0, as in "Seed Wallet 0". An account without a Display Name shows it, and renaming the wallet never changes
 * it. The pairing relies on the same rule: it sends an account named by its Auto-Name with no name, and the reader builds it
 * with this function.
 */
fun autoAccountName(baseName: String, index: Int): String = "$baseName $index"
