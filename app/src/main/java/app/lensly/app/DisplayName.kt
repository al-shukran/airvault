package app.lensly.app

/**
 * The longest Display Name, in characters after trimming. Wallets and accounts share the limit. The spec sets no length,
 * so the product sets this one.
 */
const val DISPLAY_NAME_MAX_LENGTH = 32

/**
 * True when [name] is a Display Name: 1 to [DISPLAY_NAME_MAX_LENGTH] characters, with no control character. The name is
 * checked as it is, so a caller trims it first. Names need not be unique, because the spec asks for no uniqueness.
 */
fun isValidDisplayName(name: String): Boolean =
    name.length in 1..DISPLAY_NAME_MAX_LENGTH && name.none { it.isISOControl() }

/** The Display Name [raw] gives once it is trimmed. @throws IllegalArgumentException if that is not a valid name. */
fun displayNameOf(raw: String): String {
    val name = raw.trim()
    require(isValidDisplayName(name)) {
        "A name is 1 to $DISPLAY_NAME_MAX_LENGTH characters, with no control character."
    }
    return name
}

/**
 * The Auto-Name of account [index] of a wallet whose name at creation was [baseName]: that name, a space, and the index
 * counted from 0, as in "Seed Wallet 0". An account without a Display Name shows it, and renaming the wallet never changes
 * it.
 */
fun autoAccountName(baseName: String, index: Int): String = "$baseName $index"
