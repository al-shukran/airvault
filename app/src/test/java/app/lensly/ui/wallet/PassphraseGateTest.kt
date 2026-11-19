package app.lensly.ui.wallet

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PassphraseGateTest {

    @Test
    fun aWalletWithoutPassphraseHasNoWriteDownStepAndSavesAtOnce() {
        assertFalse(PassphraseGate.needsWriteDown(null))
        assertFalse(PassphraseGate.needsWriteDown(""))
        assertTrue(PassphraseGate.canSave(null, writtenDown = false))
        assertTrue(PassphraseGate.canSave("", writtenDown = false))
    }

    @Test
    fun aWalletWithPassphraseIsNotSavedUntilTheWriteDownIsConfirmed() {
        assertTrue(PassphraseGate.needsWriteDown("TREZOR"))
        assertFalse(PassphraseGate.canSave("TREZOR", writtenDown = false))
        assertTrue(PassphraseGate.canSave("TREZOR", writtenDown = true))
    }

    @Test
    fun aReEntryMatchingTheFirstEntryIsAccepted() {
        assertTrue(PassphraseGate.matches("TREZOR".toCharArray(), "TREZOR".toCharArray()))
    }

    @Test
    fun aReEntryThatDiffersFromTheFirstEntryIsRefused() {
        assertFalse(PassphraseGate.matches("TREZOR".toCharArray(), "TREZOR!".toCharArray()))
        assertFalse(PassphraseGate.matches("TREZOR".toCharArray(), "trezor".toCharArray()))
        assertFalse(PassphraseGate.matches("TREZOR".toCharArray(), "".toCharArray()))
    }

    @Test
    fun aReEntryWithNoFirstEntryIsRefused() {
        assertFalse(PassphraseGate.matches(null, "TREZOR".toCharArray()))
    }
}
