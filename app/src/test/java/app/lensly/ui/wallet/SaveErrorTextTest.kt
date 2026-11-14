package app.lensly.ui.wallet

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.navigation.SAVE_FAILED_MESSAGE
import app.lensly.ui.theme.LenslyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A failed wallet save says so on the name screen and on the passphrase write-down, and a screen with no failure says nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class SaveErrorTextTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFailedSaveShowsItsErrorOnTheNameScreen() {
        compose.setContent {
            LenslyTheme {
                WalletNameScreen(
                    name = "",
                    onKey = {},
                    onBackspace = {},
                    onDone = {},
                    placeholder = "WALLET 1",
                    errorMessage = SAVE_FAILED_MESSAGE,
                )
            }
        }

        compose.onNodeWithText(SAVE_FAILED_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun theNameScreenShowsNoErrorBeforeASaveFails() {
        compose.setContent {
            LenslyTheme {
                WalletNameScreen(name = "", onKey = {}, onBackspace = {}, onDone = {}, placeholder = "WALLET 1")
            }
        }

        compose.onAllNodesWithText(SAVE_FAILED_MESSAGE).assertCountEquals(0)
    }

    @Test
    fun aFailedSaveShowsItsErrorOnThePassphraseBackupScreen() {
        compose.setContent {
            LenslyTheme {
                PassphraseBackupScreen(
                    writtenDown = true,
                    onWrittenDownChange = {},
                    onCreate = {},
                    errorMessage = SAVE_FAILED_MESSAGE,
                )
            }
        }

        compose.onNodeWithText(SAVE_FAILED_MESSAGE).assertIsDisplayed()
    }
}
