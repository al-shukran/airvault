package app.lensly.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.ui.camera.EntryGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The Hidden Entry Gesture, kept outside the vault. The camera needs the gesture before anyone unlocks the vault, so it
 * cannot live in Secure Storage. It sits in a plain preference file named like the camera's own settings. The file holds
 * the gesture's number and nothing else, so it reveals no wallet. Backup excludes it with the rest of the app data.
 *
 * A missing or damaged value reads as [EntryGesture.DEFAULT].
 */
class EntryGestureStore(private val prefs: SharedPreferences) {

    /** The saved gesture, or the default. */
    fun load(): EntryGesture = try {
        EntryGesture.fromStoredId(prefs.getInt(KEY, EntryGesture.DEFAULT.storedId))
    } catch (e: ClassCastException) {
        EntryGesture.DEFAULT
    }

    /** Saves [gesture]. Returns false when the write failed, and the stored gesture is then unchanged. */
    fun save(gesture: EntryGesture): Boolean = prefs.edit().putInt(KEY, gesture.storedId).commit()

    companion object {
        /** The preference file. Its name says camera, not the vault. */
        const val FILE = "camera_prefs"

        /** The key holding the gesture number. */
        internal const val KEY = "viewfinder_gesture"

        fun open(context: Context): EntryGestureStore =
            EntryGestureStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/**
 * The gesture the camera uses now, as observable state, so the camera and Settings show a change at once. [save] writes
 * the store off the main thread and makes the new gesture current only once it is on disk.
 */
class EntryGestureHolder(private val store: EntryGestureStore) {
    var current: EntryGesture by mutableStateOf(store.load())
        private set

    /**
     * Saves [gesture]. A save that has started runs to its end, even if the caller has left the screen. Returns false
     * when the write failed, and then the current gesture does not change.
     */
    suspend fun save(gesture: EntryGesture): Boolean {
        val saved = withContext(Dispatchers.IO + NonCancellable) { store.save(gesture) }
        if (saved) current = gesture
        return saved
    }
}
