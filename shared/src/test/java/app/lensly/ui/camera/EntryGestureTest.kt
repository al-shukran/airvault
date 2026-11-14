package app.lensly.ui.camera

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class EntryGestureTest {

    @Test
    fun theDefaultIsTheTwoFingerDoubleTapThenHold() {
        assertEquals(EntryGesture.DOUBLE_TAP_HOLD, EntryGesture.DEFAULT)
    }

    @Test
    fun thereAreFourGesturesNumberedOneToFour() {
        assertEquals(listOf(1, 2, 3, 4), EntryGesture.entries.map { it.storedId })
    }

    @Test
    fun everyStoredIdReadsBackItsGesture() {
        for (gesture in EntryGesture.entries) {
            assertEquals(gesture, EntryGesture.fromStoredId(gesture.storedId))
        }
    }

    @Test
    fun anUnknownStoredIdReadsAsTheDefault() {
        for (id in listOf(0, 5, -1, Int.MAX_VALUE)) {
            assertEquals(EntryGesture.DEFAULT, EntryGesture.fromStoredId(id), "id $id")
        }
    }

    @Test
    fun everyGestureHasItsOwnWordsForTheSettingsList() {
        val titles = EntryGesture.entries.map { it.title }
        val descriptions = EntryGesture.entries.map { it.description }
        assertTrue(titles.all { it.isNotBlank() })
        assertTrue(descriptions.all { it.isNotBlank() })
        assertEquals(titles.size, titles.toSet().size)
        assertNotEquals(descriptions.first(), descriptions.last())
    }
}
