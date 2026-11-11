package app.lensly.ui.wallet

/**
 * The rules of the passphrase write-down. A passphrase is part of the wallet's only backup, so a wallet with one is not
 * saved until the owner has confirmed it is written down. Without a passphrase there is no write-down step.
 */
object PassphraseGate {
    /** True when the wallet has a passphrase, so its write-down step comes before the save. An empty one is none. */
    fun needsWriteDown(passphrase: String?): Boolean = !passphrase.isNullOrEmpty()

    /** True when the wallet may be saved: no passphrase, or a passphrase the owner has confirmed is written down. */
    fun canSave(passphrase: String?, writtenDown: Boolean): Boolean = !needsWriteDown(passphrase) || writtenDown

    /** True when the re-entry in [second] equals the first entry. A missing first entry never matches. */
    fun matches(first: CharArray?, second: CharArray): Boolean = first != null && first.contentEquals(second)
}
