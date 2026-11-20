package app.lensly.app

import app.lensly.app.export.PairingCodec

/**
 * The longest Display Name, in characters after trimming. Wallets and accounts share the limit. The spec sets no length,
 * so the product sets this one.
 */
const val DISPLAY_NAME_MAX_LENGTH = 32

/**
 * True when [name] is a Display Name: 1 to [DISPLAY_NAME_MAX_LENGTH] characters, with no control or hidden format character (a
 * bidi override, for one) and no question mark. The name is checked as it is, so a caller trims it first. Names need not be unique,
 * because the spec asks for no uniqueness.
 */
fun isValidDisplayName(name: String): Boolean =
    name.length in 1..DISPLAY_NAME_MAX_LENGTH && isPlainName(name)

/**
 * True when [text] has no control or hidden format character and no question mark. A review sentence names its accounts and
 * counterparties inside its own text, and a question mark there reads as the end of the sentence.
 */
internal fun isPlainName(text: String): Boolean = PairingCodec.hasNoHiddenCharacter(text) && '?' !in text

/** The Display Name [raw] gives once it is trimmed. @throws IllegalArgumentException if that is not a valid name. */
fun displayNameOf(raw: String): String {
    val name = raw.trim()
    require(isValidDisplayName(name)) {
        "A name is 1 to $DISPLAY_NAME_MAX_LENGTH characters, with no control, hidden or question mark character."
    }
    return name
}

/**
 * The longest base name a batch rename of accounts [indices] can give. Each account gets "<base> <index>", which must still
 * be a Display Name, so the base gives up one character for the space and the digits of the largest index.
 */
fun batchBaseMaxLength(indices: Collection<Int>): Int =
    DISPLAY_NAME_MAX_LENGTH - 1 - (indices.maxOrNull() ?: 0).toString().length

/**
 * True when [base] can name accounts [indices] in a batch rename: 1 to [batchBaseMaxLength] characters, with no control or hidden
 * format character and no question mark. The caller trims it first. The spec sets no batch format, so each account is named as its
 * Auto-Name would be.
 */
fun isValidBatchBase(base: String, indices: Collection<Int>): Boolean =
    base.length in 1..batchBaseMaxLength(indices) && isPlainName(base)
