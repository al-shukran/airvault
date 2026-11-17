package app.lensly.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** The copy helper. Every copy is marked sensitive, and it is cleared a minute later unless it was replaced or a newer copy was made. */
@RunWith(RobolectricTestRunner::class)
class ClipboardTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard: ClipboardManager = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun theCopyHoldsTheTextUnderItsLabelAndIsMarkedSensitive() {
        copyToClipboard(context, "Address", ADDRESS)

        assertEquals(ADDRESS, heldText())
        assertEquals("Address", clipboard.primaryClipDescription?.label?.toString())
        assertTrue(clipboard.primaryClipDescription?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)
    }

    @Test
    fun theClipIsClearedSixtySecondsAfterTheCopy() {
        copyToClipboard(context, "Address", ADDRESS)

        ShadowLooper.idleMainLooper(CLIPBOARD_CLEAR_DELAY_MS - 1, TimeUnit.MILLISECONDS)
        assertEquals(ADDRESS, heldText())

        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun aClipThatWasReplacedIsNotCleared() {
        copyToClipboard(context, "Address", ADDRESS)
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "copied in another app"))

        ShadowLooper.idleMainLooper(CLIPBOARD_CLEAR_DELAY_MS, TimeUnit.MILLISECONDS)

        assertEquals("copied in another app", heldText())
    }

    @Test
    fun aNewerCopyOfTheSameTextPushesTheClearBack() {
        copyToClipboard(context, "Address", ADDRESS)
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        copyToClipboard(context, "Address", ADDRESS)

        // The first copy's clear falls due here, but a newer copy was made, so the text stays.
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        assertEquals(ADDRESS, heldText())

        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS)
        assertFalse(clipboard.hasPrimaryClip())
    }

    private fun heldText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private companion object {
        const val ADDRESS = "0x52908400098527886E0F7030069857D2E4169EE7"
    }
}
