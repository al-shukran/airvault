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
 * The label of an account with no custom name. The first account is "Account 1", as the list has always numbered them.
 * An account keeps this label until it is renamed.
 */
fun defaultAccountLabel(index: Int): String = "Account ${index + 1}"
