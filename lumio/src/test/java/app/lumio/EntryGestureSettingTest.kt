package app.lumio

import android.content.Context
import app.lensly.app.EntryGestureHolder
import app.lensly.app.EntryGestureStore
import app.lensly.ui.camera.EntryGesture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Lumio's Hidden Entry Gesture. Settings saves it to the camera's own preference file, and the cover reads it from there, so the gesture
 * the owner chose is the one the gate answers to, also after the app starts again.
 */
@RunWith(RobolectricTestRunner::class)
class EntryGestureSettingTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun cameraPrefs() = context.getSharedPreferences(EntryGestureStore.FILE, Context.MODE_PRIVATE)

    @Before
    fun startWithNoSavedGesture() {
        cameraPrefs().edit().clear().commit()
    }

    @Test
    fun aGestureChosenInSettingsIsSavedToTheCameraPreferences() = runBlocking {
        val holder = EntryGestureHolder(EntryGestureStore.open(context))

        assertTrue(holder.save(EntryGesture.THUMBNAIL_HOLD_SWIPE))

        assertEquals(EntryGesture.THUMBNAIL_HOLD_SWIPE.storedId, cameraPrefs().getInt(EntryGestureStore.KEY, -1))
    }

    @Test
    fun theCoverReadsTheSavedGestureWhenTheAppStartsAgain() = runBlocking {
        EntryGestureHolder(EntryGestureStore.open(context)).save(EntryGesture.PINCH_TRIPLE_TAP)

        // The app builds a new holder at each start, and the cover takes the gesture it gate-checks from that holder.
        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, EntryGestureHolder(EntryGestureStore.open(context)).current)
    }

    @Test
    fun theCoverTakesTheNewGestureAtOnceWhenItIsSaved() = runBlocking {
        val holder = EntryGestureHolder(EntryGestureStore.open(context))
        assertEquals(EntryGesture.DEFAULT, holder.current)

        holder.save(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

        // The cover composes from holder.current, so the next gesture it reads is the saved one.
        assertEquals(EntryGesture.SWITCH_THEN_SHUTTER_HOLD, holder.current)
    }

    @Test
    fun withNothingSavedTheCoverUsesTheDefault() {
        assertEquals(EntryGesture.DEFAULT, EntryGestureHolder(EntryGestureStore.open(context)).current)
    }
}
