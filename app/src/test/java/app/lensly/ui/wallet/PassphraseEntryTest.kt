package app.lensly.ui.wallet

import app.lensly.app.WalletDraft
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PassphraseEntryTest {

    // BIP39 vector for 256 zero bits of entropy, as in MnemonicWipeRecompositionTest.
    private val artWords = List(23) { "abandon" } + "art"

    private val settled = mutableListOf<String?>()
    private val entry = PassphraseEntry { settled += it }

    private fun type(text: String) = text.forEach { entry.key(it) }

    @Test
    fun aMatchingConfirmSettlesThePassphrase() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        assertEquals(PassphraseStep.Confirm, entry.step)
        type("TREZOR")
        entry.submit()
        assertEquals(listOf<String?>("TREZOR"), settled)
    }

    @Test
    fun anEmptyEnterSettlesNoPassphrase() {
        entry.startEntry()
        entry.submit()
        assertEquals(listOf<String?>(null), settled)
    }

    @Test
    fun aMismatchReturnsToEnterWithBothEntriesCleared() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()

        assertEquals(PassphraseStep.Enter, entry.step)
        assertTrue(entry.mismatch)
        assertEquals(0, entry.length)
        assertEquals(emptyList(), settled)
    }

    @Test
    fun typingAfterAMismatchClearsTheMismatchMessage() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()
        type("T")
        assertFalse(entry.mismatch)
        assertEquals(1, entry.length)
    }

    @Test
    fun theConfirmIsCheckedAgainstTheNewEnterEntryAfterAMismatch() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()
        type("NEW")
        entry.submit()
        type("TREZOR")
        entry.submit()

        assertEquals(PassphraseStep.Enter, entry.step)
        assertTrue(entry.mismatch)
        assertEquals(emptyList(), settled)
    }

    @Test
    fun aMatchAfterAMismatchSettlesTheNewPassphrase() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()
        type("NEW")
        entry.submit()
        type("NEW")
        entry.submit()
        assertEquals(listOf<String?>("NEW"), settled)
    }

    @Test
    fun anEmptyEnterAfterAMismatchDoesNotDropThePassphrase() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()
        entry.submit()

        assertEquals(PassphraseStep.Enter, entry.step)
        assertTrue(entry.mismatch)
        assertEquals(emptyList(), settled)
    }

    @Test
    fun aBackspaceOnAnEmptyEntryAfterAMismatchDoesNotDropThePassphrase() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()
        entry.backspace()
        assertTrue(entry.mismatch)
        entry.submit()

        assertEquals(PassphraseStep.Enter, entry.step)
        assertTrue(entry.mismatch)
        assertEquals(emptyList(), settled)
    }

    @Test
    fun aTypedCharThenBackspaceAfterAMismatchDoesNotDropThePassphrase() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TREZER")
        entry.submit()
        type("T")
        entry.backspace()
        entry.submit()

        assertEquals(PassphraseStep.Enter, entry.step)
        assertEquals(emptyList(), settled)
    }

    @Test
    fun aMismatchKeepsTheWordsOfTheDraft() {
        val draft = WalletDraft()
        draft.setTypedWords(artWords)
        val restore = PassphraseEntry { draft.setPassphrase(it) }

        restore.startEntry()
        "TREZOR".forEach { restore.key(it) }
        restore.submit()
        "TREZER".forEach { restore.key(it) }
        restore.submit()

        assertEquals(PassphraseStep.Enter, restore.step)
        assertEquals(artWords, draft.words.toList())
        assertNull(draft.passphrase)
    }

    @Test
    fun skipSettlesNoPassphraseWhateverIsTyped() {
        entry.startEntry()
        type("TREZOR")
        entry.submit()
        type("TRE")
        entry.skip()
        assertEquals(listOf<String?>(null), settled)
    }
}
