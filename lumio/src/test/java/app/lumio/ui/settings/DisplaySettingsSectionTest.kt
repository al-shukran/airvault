package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.lensly.ui.theme.LenslyTheme
import app.lumio.data.config.DisplaySettings
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Home section of the settings: its four settings show their values, and a change is reported once it is valid. */
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var showTotal: Boolean? = null
    private var showAssets: Boolean? = null
    private var hideSpam: Boolean? = null
    private var minimumUsd: Double? = null

    private fun show(settings: DisplaySettings = DisplaySettings(), notice: String? = null) {
        compose.setContent {
            LenslyTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DisplaySettingsSection(
                        settings = settings,
                        notice = notice,
                        onShowTotal = { showTotal = it },
                        onShowAssets = { showAssets = it },
                        onMinimumUsd = { minimumUsd = it },
                        onHideSpam = { hideSpam = it },
                    )
                }
            }
        }
    }

    @Test
    fun theFourSettingsShowTheirValues() {
        show()

        compose.onNodeWithText("Show total balance").assertExists()
        compose.onNodeWithText("Show individual asset balances").assertExists()
        compose.onNodeWithText("Hide spam and scam transactions").assertExists()
        compose.onNodeWithText("Minimum balance (USD)").assertExists()
        compose.onNodeWithText("1.00").assertExists()
    }

    @Test
    fun aSwitchReportsItsNewValue() {
        show()

        compose.onNodeWithText("Show total balance").performClick()

        assertEquals(true, showTotal)
        assertNull(showAssets)
    }

    @Test
    fun theMinimumIsSavedWhenTheOwnerPressesDone() {
        show()

        compose.onNodeWithText("1.00").performTextReplacement("2.5")
        compose.onNodeWithText("2.5").performImeAction()

        assertEquals(2.5, minimumUsd)
    }

    @Test
    fun aMinimumThatIsNotAnAmountIsRefusedAndSavesNothing() {
        show()

        compose.onNodeWithText("1.00").performTextReplacement("-1")
        compose.onNodeWithText("-1").performImeAction()

        assertNull(minimumUsd)
        compose.onNodeWithText("Enter an amount from 0 to 1,000,000, with at most two decimals.").assertExists()
    }

    @Test
    fun aFailedSaveIsShownAsANotice() {
        show(notice = "The setting could not be saved. Try again.")

        compose.onNodeWithText("The setting could not be saved. Try again.").assertExists()
    }
}
