package app.lensly.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The address text. A copy button and a long press put the full address on the clipboard, not the shortened text on the screen, and
 * say so with a toast. The clip is sensitive, so the clipboard preview hides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AddressTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val clipboard: ClipboardManager
        get() = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)

    @Test
    fun theCopyButtonPutsTheWholeAddressOnTheClipboardAndSaysSo() {
        setAddress()

        compose.onNodeWithContentDescription(COPY_ADDRESS_LABEL).performClick()

        assertEquals(ADDRESS, clipboardText())
        assertEquals(ADDRESS_COPIED, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aLongPressOnTheAddressCopiesTheWholeAddress() {
        setAddress()

        compose.onNodeWithTag(ADDRESS_TEXT_TAG).performTouchInput { longClick() }

        assertEquals(ADDRESS, clipboardText())
        assertEquals(ADDRESS_COPIED, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theScreenShowsTheShortTextWhileTheClipHoldsTheFullAddress() {
        setAddress()

        compose.onNodeWithText(SHORT).assertExists()
        compose.onNodeWithContentDescription(COPY_ADDRESS_LABEL).performClick()

        assertEquals(ADDRESS, clipboardText())
    }

    @Test
    fun theClipIsMarkedSensitive() {
        setAddress()

        compose.onNodeWithContentDescription(COPY_ADDRESS_LABEL).performClick()

        val extras = clipboard.primaryClipDescription?.extras
        assertTrue(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)
    }

    @Test
    fun aTapOnTheTextRunsItsOnClickAndDoesNotCopy() {
        clipboard.setPrimaryClip(ClipData.newPlainText("before", "unchanged"))
        var tapped = false
        setAddress(onClick = { tapped = true })

        compose.onNodeWithTag(ADDRESS_TEXT_TAG).performClick()

        assertTrue(tapped)
        assertEquals("unchanged", clipboardText())
    }

    @Test
    fun aRowShowsNoEthereumLogoUnlessItAsksForOne() {
        setAddress()

        compose.onNodeWithTag(ADDRESS_ICON_TAG).assertDoesNotExist()
    }

    @Test
    fun anEvmRowShowsTheEthereumLogoWhenItAsksForIt() {
        setAddress(icon = true)

        compose.onNodeWithTag(ADDRESS_ICON_TAG).assertExists()
    }

    private fun setAddress(icon: Boolean = false, onClick: (() -> Unit)? = null) {
        compose.setContent {
            LumenTheme {
                AddressText(address = ADDRESS, shown = SHORT, icon = icon, onClick = onClick)
            }
        }
    }

    private fun clipboardText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private companion object {
        const val ADDRESS = "0x52908400098527886E0F7030069857D2E4169EE7"
        const val SHORT = "0x529084…69EE7"
    }
}
