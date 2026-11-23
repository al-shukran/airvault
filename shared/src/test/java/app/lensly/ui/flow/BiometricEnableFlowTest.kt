package app.lensly.ui.flow

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Turning Biometric Unlock on asks for the App Password first. Only a right one reaches the biometric prompt, so a wrong password never seals
 * the Vault Key. The ports are fakes here; the vault-backed case is in BiometricUnlockTest.
 */
class BiometricEnableFlowTest {

    private class FakePorts(private val check: PasswordCheck, private val seals: Boolean = true) : BiometricEnablePorts {
        var prompts = 0
            private set

        override suspend fun checkPassword(password: CharArray): PasswordCheck = check

        override suspend fun enable(): Boolean {
            prompts++
            return seals
        }
    }

    @Test
    fun aWrongPasswordChangesNothingAndNeverRunsThePrompt() = runBlocking {
        val ports = FakePorts(PasswordCheck.Rejected)
        val flow = BiometricEnableFlow(ports)

        flow.submitPassword("wrong".toCharArray())

        assertEquals(BiometricEnableStep.Password(busy = false), flow.state.value)
        assertEquals(0, ports.prompts)
    }

    @Test
    fun aRightPasswordRunsThePromptAndTurnsItOn() = runBlocking {
        val ports = FakePorts(PasswordCheck.Accepted)
        val flow = BiometricEnableFlow(ports)

        flow.submitPassword("right".toCharArray())

        assertEquals(BiometricEnableStep.Ended(BiometricEnableOutcome.ON), flow.state.value)
        assertEquals(1, ports.prompts)
    }

    @Test
    fun aCancelledPromptEndsWithNothingOn() = runBlocking {
        val ports = FakePorts(PasswordCheck.Accepted, seals = false)
        val flow = BiometricEnableFlow(ports)

        flow.submitPassword("right".toCharArray())

        assertEquals(BiometricEnableStep.Ended(BiometricEnableOutcome.OFF), flow.state.value)
    }

    @Test
    fun aVaultThatCannotBeCheckedEndsUnavailableWithoutRunningThePrompt() = runBlocking {
        val ports = FakePorts(PasswordCheck.Unavailable)
        val flow = BiometricEnableFlow(ports)

        flow.submitPassword("any".toCharArray())

        assertEquals(BiometricEnableStep.Ended(BiometricEnableOutcome.UNAVAILABLE), flow.state.value)
        assertEquals(0, ports.prompts)
    }

    @Test
    fun thePasswordIsZeroedOnceItHasBeenChecked() = runBlocking {
        val password = "secret".toCharArray()

        BiometricEnableFlow(FakePorts(PasswordCheck.Rejected)).submitPassword(password)

        assertTrue(password.all { it == '\u0000' })
    }
}
