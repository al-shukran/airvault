package app.lumio.ui.firstrun

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Step I8: Set Up Now opens the Duress Password page, Maybe Later goes on, and the line under the question names where it can be set later. */
@RunWith(RobolectricTestRunner::class)
class DuressScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun setUpNowOpensTheDuressPasswordAndMaybeLaterGoesOn() {
        val taps = mutableListOf<String>()
        compose.setContent { LumenTheme { ClearAirTheme { DuressScreen(onSetUp = { taps += "set" }, onMaybeLater = { taps += "later" }) } } }

        compose.onNodeWithText("You can set it up later in Settings → Security.").assertExists()
        compose.onNodeWithText("Set Up Now").performClick()
        compose.onNodeWithText("Maybe Later").performClick()

        assertEquals(listOf("set", "later"), taps)
    }
}
