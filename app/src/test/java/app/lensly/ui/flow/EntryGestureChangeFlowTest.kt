package app.lensly.ui.flow

import app.lensly.ui.camera.EntryGesture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

private const val PASSWORD = "cobalt lantern marsh vivid tundra pepper"

/** Answers each password check, and says whether each save worked. Both are counted. */
private class FakeGesturePorts(
    private val answer: (CharArray) -> PasswordCheck = { PasswordCheck.Accepted },
    private val saves: Boolean = true,
) : EntryGesturePorts {
    val checked = mutableListOf<CharArray>()
    val saved = mutableListOf<EntryGesture>()

    override suspend fun checkPassword(password: CharArray): PasswordCheck {
        checked += password
        return answer(password)
    }

    override suspend fun save(gesture: EntryGesture): Boolean {
        saved += gesture
        return saves
    }
}

class EntryGestureChangeFlowTest {

    @Test
    fun startsOnThePasswordStepAndSavesNothing() {
        val ports = FakeGesturePorts()
        val flow = EntryGestureChangeFlow(EntryGesture.PINCH_TRIPLE_TAP, ports)

        assertEquals(EntryGestureStep.Password(busy = false), flow.state.value)
        assertTrue(ports.saved.isEmpty())
    }

    @Test
    fun aWrongPasswordStaysOnThePasswordStepAndSavesNothing() = runBlocking<Unit> {
        val ports = FakeGesturePorts(answer = { PasswordCheck.Rejected })
        val flow = EntryGestureChangeFlow(EntryGesture.PINCH_TRIPLE_TAP, ports)

        flow.submitPassword(PASSWORD.toCharArray())

        assertEquals(EntryGestureStep.Password(busy = false), flow.state.value)
        assertTrue(ports.saved.isEmpty())
        // The typed password is zeroed, whether it was right or not.
        assertTrue(ports.checked.single().all { it == '\u0000' })
    }

    @Test
    fun aRightPasswordOpensThePracticeWithoutSaving() = runBlocking<Unit> {
        val ports = FakeGesturePorts()
        val flow = EntryGestureChangeFlow(EntryGesture.PINCH_TRIPLE_TAP, ports)

        flow.submitPassword(PASSWORD.toCharArray())

        assertEquals(EntryGestureStep.Practice(busy = false), flow.state.value)
        assertTrue(ports.saved.isEmpty())
        assertTrue(ports.checked.single().all { it == '\u0000' })
    }

    @Test
    fun aPasswordRefusedWhileTheVaultIsUnavailableEndsTheChange() = runBlocking<Unit> {
        val ports = FakeGesturePorts(answer = { PasswordCheck.Unavailable })
        val flow = EntryGestureChangeFlow(EntryGesture.PINCH_TRIPLE_TAP, ports)

        flow.submitPassword(PASSWORD.toCharArray())

        assertEquals(EntryGestureStep.Ended(EntryGestureOutcome.UNAVAILABLE), flow.state.value)
        assertTrue(ports.saved.isEmpty())
    }

    @Test
    fun thePracticeSavesTheTargetGestureOnce() = runBlocking<Unit> {
        val ports = FakeGesturePorts()
        val flow = EntryGestureChangeFlow(EntryGesture.THUMBNAIL_HOLD_SWIPE, ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.practiced()
        flow.practiced()

        assertEquals(EntryGestureStep.Ended(EntryGestureOutcome.SAVED), flow.state.value)
        assertEquals(listOf(EntryGesture.THUMBNAIL_HOLD_SWIPE), ports.saved)
    }

    @Test
    fun aFailedSaveEndsUnavailableAndClaimsNothingSaved() = runBlocking<Unit> {
        val ports = FakeGesturePorts(saves = false)
        val flow = EntryGestureChangeFlow(EntryGesture.SWITCH_THEN_SHUTTER_HOLD, ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.practiced()

        assertEquals(EntryGestureStep.Ended(EntryGestureOutcome.UNAVAILABLE), flow.state.value)
        assertEquals(listOf(EntryGesture.SWITCH_THEN_SHUTTER_HOLD), ports.saved)
    }

    @Test
    fun practicingBeforeThePasswordSavesNothing() = runBlocking<Unit> {
        val ports = FakeGesturePorts()
        val flow = EntryGestureChangeFlow(EntryGesture.DOUBLE_TAP_HOLD, ports)

        flow.practiced()

        assertEquals(EntryGestureStep.Password(busy = false), flow.state.value)
        assertTrue(ports.saved.isEmpty())
    }

    @Test
    fun aPasswordSubmittedAfterThePracticeIsIgnored() = runBlocking<Unit> {
        val ports = FakeGesturePorts()
        val flow = EntryGestureChangeFlow(EntryGesture.PINCH_TRIPLE_TAP, ports)

        flow.submitPassword(PASSWORD.toCharArray())
        flow.practiced()
        flow.submitPassword(PASSWORD.toCharArray())

        assertEquals(1, ports.checked.size)
        assertEquals(listOf(EntryGesture.PINCH_TRIPLE_TAP), ports.saved)
    }
}
