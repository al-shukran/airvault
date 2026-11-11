package app.lensly.app

import android.content.Context
import android.content.SharedPreferences
import app.lensly.ui.camera.EntryGesture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Hidden Entry Gesture kept outside the vault: its round trip, its defaults, and what the file holds. */
@RunWith(RobolectricTestRunner::class)
class EntryGestureStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(EntryGestureStore.FILE, Context.MODE_PRIVATE)

    @Before
    fun startWithAnEmptyFile() {
        prefs().edit().clear().commit()
    }

    @Test
    fun aFreshStoreReadsTheDefault() {
        assertEquals(EntryGesture.DEFAULT, EntryGestureStore(prefs()).load())
    }

    @Test
    fun everyGestureSavedIsReadBackAfterTheStoreIsOpenedAgain() {
        for (gesture in EntryGesture.entries) {
            assertTrue(EntryGestureStore(prefs()).save(gesture))
            assertEquals(gesture, EntryGestureStore(prefs()).load())
        }
    }

    @Test
    fun theLastSavedGestureWins() {
        val store = EntryGestureStore(prefs())
        store.save(EntryGesture.PINCH_TRIPLE_TAP)
        store.save(EntryGesture.THUMBNAIL_HOLD_SWIPE)

        assertEquals(EntryGesture.THUMBNAIL_HOLD_SWIPE, EntryGestureStore(prefs()).load())
    }

    @Test
    fun aStoredValueOfAnotherTypeReadsAsTheDefault() {
        prefs().edit().putString(EntryGestureStore.KEY, "three").commit()

        assertEquals(EntryGesture.DEFAULT, EntryGestureStore(prefs()).load())
    }

    @Test
    fun anUnknownStoredNumberReadsAsTheDefault() {
        prefs().edit().putInt(EntryGestureStore.KEY, 9).commit()

        assertEquals(EntryGesture.DEFAULT, EntryGestureStore(prefs()).load())
    }

    @Test
    fun theFileIsNamedAsCameraSettingsAndHoldsOnlyTheGestureNumber() {
        EntryGestureStore(prefs()).save(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

        assertEquals("camera_prefs", EntryGestureStore.FILE)
        assertEquals(setOf(EntryGestureStore.KEY), prefs().all.keys)
        assertEquals(EntryGesture.SWITCH_THEN_SHUTTER_HOLD.storedId, prefs().getInt(EntryGestureStore.KEY, 0))
    }
}
