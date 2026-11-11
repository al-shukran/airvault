package app.lensly.ui.flow

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

private const val PASSWORD = "cobalt lantern marsh vivid tundra pepper"

/** Answers each password check, and gives [outcome] for each erase. Both are counted. */
private class FakeShredPorts(
    private val answer: (CharArray) -> PasswordCheck = { PasswordCheck.Accepted },
    private val outcome: ShredOutcome = ShredOutcome.ERASED,
) : ShredPorts {
    val checked = mutableListOf<CharArray>()
    var erases = 0
        private set

    override suspend fun checkPassword(password: CharArray): PasswordCheck {
        checked += password
        return answer(password)
    }

    override suspend fun erase(): ShredOutcome {
        erases++
        return outcome
    }
}

class ShredFlowTest {

    @Test
    fun startsOnThePasswordStepAndErasesNothing() {
        val ports = FakeShredPorts()
        val flow = ShredFlow(ports)

        assertEquals(ShredStep.Password(busy = false), flow.state.value)
        assertEquals(0, ports.erases)
    }

    @Test
    fun aWrongPasswordRefusesWithNothingShownAndErasesNothing() = runBlocking<Unit> {
        val ports = FakeShredPorts(answer = { PasswordCheck.Rejected })
        val flow = ShredFlow(ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.confirm()

        assertEquals(ShredStep.Password(busy = false), flow.state.value)
        assertEquals(0, ports.erases)
        // The typed password is zeroed, whether it was right or not.
        assertTrue(ports.checked.single().all { it == '\u0000' })
    }

    @Test
    fun aRightPasswordOpensTheWarningWithoutErasing() = runBlocking<Unit> {
        val ports = FakeShredPorts()
        val flow = ShredFlow(ports)

        flow.submitPassword(PASSWORD.toCharArray())

        assertEquals(ShredStep.Confirm(busy = false), flow.state.value)
        assertEquals(0, ports.erases)
    }

    @Test
    fun confirmingErasesOnceAndEnds() = runBlocking<Unit> {
        val ports = FakeShredPorts()
        val flow = ShredFlow(ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.confirm()
        flow.confirm()

        assertEquals(ShredStep.Ended(ShredOutcome.ERASED), flow.state.value)
        assertEquals(1, ports.erases)
    }

    @Test
    fun confirmBeforeThePasswordErasesNothing() = runBlocking<Unit> {
        val ports = FakeShredPorts()
        val flow = ShredFlow(ports)

        flow.confirm()

        assertEquals(ShredStep.Password(busy = false), flow.state.value)
        assertEquals(0, ports.erases)
    }

    @Test
    fun aPasswordCheckThatCannotRunEndsUnavailableWithoutErasing() = runBlocking<Unit> {
        val ports = FakeShredPorts(answer = { PasswordCheck.Unavailable })
        val flow = ShredFlow(ports)

        flow.submitPassword(PASSWORD.toCharArray())

        assertEquals(ShredStep.Ended(ShredOutcome.UNAVAILABLE), flow.state.value)
        assertEquals(0, ports.erases)
    }

    @Test
    fun aLockedVaultAtTheEraseEndsLocked() = runBlocking<Unit> {
        val ports = FakeShredPorts(outcome = ShredOutcome.LOCKED)
        val flow = ShredFlow(ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.confirm()

        assertEquals(ShredStep.Ended(ShredOutcome.LOCKED), flow.state.value)
    }

    @Test
    fun anEraseThatFailsIsReportedAsUnavailable() = runBlocking<Unit> {
        val ports = FakeShredPorts(outcome = ShredOutcome.UNAVAILABLE)
        val flow = ShredFlow(ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.confirm()

        assertEquals(ShredStep.Ended(ShredOutcome.UNAVAILABLE), flow.state.value)
        assertEquals(1, ports.erases)
    }
}
