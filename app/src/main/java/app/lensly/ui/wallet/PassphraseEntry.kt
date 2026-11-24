package app.lensly.ui.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.core.wallet.LedgerPassphraseIssue
import app.lensly.core.wallet.ledgerPassphraseIssue
import app.lensly.ui.gate.PasswordBuffer

private const val NUL = '\u0000'

/**
 * The optional passphrase as the owner types it on [PassphraseScreen]: the Enter entry, then the Confirm entry. A Confirm entry
 * that differs from the Enter entry goes back to Enter with both entries cleared and the mismatch shown. Nothing else changes,
 * so the words of the restore or create flow stay in their draft. [onSettled] gets the passphrase once it is settled, or null
 * for none.
 */
internal class PassphraseEntry(private val onSettled: (passphrase: String?) -> Unit) {
    private val buffer = PasswordBuffer()

    /** The Enter entry, kept while Confirm is typed. Null in every other step. */
    private var first: CharArray? = null

    var step by mutableStateOf(PassphraseStep.Ask)
        private set

    /** The number of characters in the entry being typed. */
    var length by mutableIntStateOf(0)
        private set

    var mismatch by mutableStateOf(false)
        private set

    /** Set from the Enter entry while it is typed, and kept while Confirm is typed. */
    var ledgerIssue by mutableStateOf<LedgerPassphraseIssue?>(null)
        private set

    /** The owner chose to add a passphrase. */
    fun startEntry() {
        step = PassphraseStep.Enter
    }

    fun key(c: Char) {
        if (buffer.append(c)) typed()
    }

    fun backspace() {
        if (buffer.isEmpty()) return
        buffer.backspace()
        typed()
    }

    /** Submits the entry on the screen, and moves the entry on from its step. */
    fun submit() {
        when (step) {
            PassphraseStep.Ask -> Unit
            PassphraseStep.Enter -> when {
                !buffer.isEmpty() -> {
                    first = buffer.toCharArray()
                    buffer.clear()
                    length = 0
                    step = PassphraseStep.Confirm
                }
                // An empty entry means no passphrase. Right after a mismatch it must not drop the passphrase silently.
                !mismatch -> skip()
            }
            PassphraseStep.Confirm -> {
                val second = buffer.toCharArray()
                val matches = PassphraseGate.matches(first, second)
                second.fill(NUL)
                if (matches) {
                    val passphrase = String(checkNotNull(first))
                    clear()
                    onSettled(passphrase)
                } else {
                    clear()
                    mismatch = true
                    step = PassphraseStep.Enter
                }
            }
        }
    }

    /** Clears both entries and settles on no passphrase. */
    fun skip() {
        clear()
        onSettled(null)
    }

    /** Zeroes both entries and empties the count. The screen calls it when it leaves. */
    fun clear() {
        buffer.clear()
        first?.fill(NUL)
        first = null
        length = 0
        ledgerIssue = null
    }

    private fun typed() {
        length = buffer.length
        mismatch = false
        if (step == PassphraseStep.Enter) ledgerIssue = ledgerIssueOf()
    }

    /**
     * The Ledger issue of the typed entry, or null when it is empty or can be typed on a Ledger. The check reads a copy of the
     * characters, and the copy is zeroed before this returns. No String of the passphrase is made.
     */
    private fun ledgerIssueOf(): LedgerPassphraseIssue? {
        if (buffer.isEmpty()) return null
        val chars = buffer.toCharArray()
        try {
            return ledgerPassphraseIssue(chars)
        } finally {
            chars.fill(NUL)
        }
    }
}
