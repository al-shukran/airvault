package app.lensly.ui.components

import kotlin.test.assertEquals
import org.junit.Test

class ClipboardStaleCopyTest {

    private val copiedAt = 1_000L

    @Test
    fun aFreshCopyIsKeptWhetherOrNotItIsStillHeld() {
        assertEquals(
            StaleCopyAction.KeepWatching,
            staleCopyAction(copiedAt, copiedAt + CLIPBOARD_CLEAR_DELAY_MS - 1, "addr", "addr"),
        )
    }

    @Test
    fun aCopyDueAtTheDelayStillHeldIsCleared() {
        assertEquals(
            StaleCopyAction.Clear,
            staleCopyAction(copiedAt, copiedAt + CLIPBOARD_CLEAR_DELAY_MS, "addr", "addr"),
        )
    }

    @Test
    fun anOldCopyThatCannotBeReadIsKeptForTheNextFocus() {
        assertEquals(
            StaleCopyAction.KeepWatching,
            staleCopyAction(copiedAt, copiedAt + 5 * CLIPBOARD_CLEAR_DELAY_MS, "addr", null),
        )
    }

    @Test
    fun anOldCopyThatWasReplacedIsForgotten() {
        assertEquals(
            StaleCopyAction.Forget,
            staleCopyAction(copiedAt, copiedAt + 5 * CLIPBOARD_CLEAR_DELAY_MS, "addr", "something else"),
        )
    }
}
