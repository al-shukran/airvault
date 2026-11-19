package app.lumio.ui.kit

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** A Lumio copy control puts its value on the clipboard as Lensly's copies are: marked sensitive, and cleared a minute later. */
@RunWith(RobolectricTestRunner::class)
class CopyActionTest {
    @get:Rule
    val compose = createComposeRule()

    private val clipboard: ClipboardManager =
        RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun aCopyIsMarkedSensitiveAndClearedAfterAMinute() {
        lateinit var action: CopyAction
        compose.setContent { action = rememberCopyAction(value = ADDRESS, label = "Address") }

        // The copy runs on the test thread: waiting on the compose tree would move the paused clock past the clear time before the checks below.
        action.copy()

        assertEquals(ADDRESS, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("Address", clipboard.primaryClipDescription?.label?.toString())
        assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)

        // The clear is due a minute after the copy's own timing, which the copy itself moves by a few milliseconds: so check well inside the minute,
        // then well past it. The exact delay is covered by the shared ClipboardTest.
        ShadowLooper.idleMainLooper(CLEAR_DELAY_MS - 1_000L, TimeUnit.MILLISECONDS)
        assertTrue(clipboard.hasPrimaryClip())

        ShadowLooper.idleMainLooper(2_000L, TimeUnit.MILLISECONDS)
        assertFalse(clipboard.hasPrimaryClip())
    }

    private companion object {
        const val ADDRESS = "0x6b175474e89094c44da98b954eedeac495271d0f"

        /** The shared copy helper's clear delay (Lensly's CLIPBOARD_CLEAR_DELAY_MS), which is internal to :shared. */
        const val CLEAR_DELAY_MS = 60_000L
    }
}
