package app.lumio.ui.kit

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Back belongs to a sheet until the sheet is gone, so a second Back during its exit animation does not move the page behind. */
@RunWith(RobolectricTestRunner::class)
class ClearAirSheetBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSecondBackDuringTheExitDoesNotReachThePageBehind() {
        var behind = 0
        var open by mutableStateOf(true)
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    BackHandler { behind++ }
                    ClearAirSheet(visible = open, onClose = { open = false }) { Text("Sheet body") }
                }
            }
        }
        compose.onNodeWithText("Sheet body").assertExists()
        compose.mainClock.autoAdvance = false

        back()
        // Part way through the exit: the sheet is closing, and its animation has not ended.
        compose.mainClock.advanceTimeBy(50)
        back()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(0, behind)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Sheet body").assertDoesNotExist()
        back()
        assertEquals(1, behind)
    }

    private fun back() = compose.runOnUiThread {
        compose.activity.onBackPressedDispatcher.onBackPressed()
        Snapshot.sendApplyNotifications()
    }
}
