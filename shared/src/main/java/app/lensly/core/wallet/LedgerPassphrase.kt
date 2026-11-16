package app.lensly.core.wallet

/** Why a BIP-39 passphrase cannot be typed on a Ledger device. */
enum class LedgerPassphraseIssue { TOO_LONG, UNTYPABLE }

/** Longest passphrase a Ledger device accepts, in characters. */
private const val LEDGER_MAX_LENGTH = 100

/** The first printable ASCII character (space) and the last (tilde): the characters a Ledger device keyboard types. */
private const val PRINTABLE_FIRST = ' '
private const val PRINTABLE_LAST = '~'

/**
 * Checks the characters of a passphrase against what a Ledger device can take. Returns null when it can be typed on a
 * Ledger, [LedgerPassphraseIssue.UNTYPABLE] when any character is outside printable ASCII (0x20 to 0x7E), and
 * [LedgerPassphraseIssue.TOO_LONG] when it is longer than 100 characters. UNTYPABLE is checked first.
 *
 * The check reads [p] as typed and does not normalise it. That is conservative: a fullwidth letter that NFKD would map to
 * an ASCII letter is still reported, because the owner should not rely on that mapping at a Ledger. [p] is read only, and
 * the caller owns it and zeroes it.
 *
 * Sources. Ledger Academy, "Passphrase: Ledger's Advanced Security Feature"
 * (https://www.ledger.com/academy/passphrase-an-advanced-security-feature): "The only limitation is using a maximum of
 * 100 characters." and "The passphrase is case-sensitive and can include numbers and symbols." The Academy page does not
 * list the permitted symbols, and it says nothing about non-ASCII characters, emoji or spaces. The printable-ASCII rule is
 * the owner's constraint for the Ledger device keyboard, not Academy text. Ledger support's "Advanced passphrase security"
 * article did not turn up in search, so the rule is not checked against a device yet (WI-22).
 */
fun ledgerPassphraseIssue(p: CharArray): LedgerPassphraseIssue? {
    if (p.any { it < PRINTABLE_FIRST || it > PRINTABLE_LAST }) return LedgerPassphraseIssue.UNTYPABLE
    if (p.size > LEDGER_MAX_LENGTH) return LedgerPassphraseIssue.TOO_LONG
    return null
}
