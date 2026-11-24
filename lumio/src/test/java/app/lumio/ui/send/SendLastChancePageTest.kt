package app.lumio.ui.send

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Last Chance with its count run out: the page says the send is going out and offers no stop, and it never reads a countdown to zero. */
@RunWith(RobolectricTestRunner::class)
class SendLastChancePageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aCountThatHasRunOutSaysTheSendIsGoingOut() {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    SendLastChancePage(
                        recipient = "FelixStone",
                        amount = "0.25 MON",
                        remaining = 0,
                        total = 0,
                        sending = false,
                        onSendNow = {},
                        onStop = {},
                        backLabel = "Review",
                        offline = null,
                        onBack = {},
                        onLock = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Sending 0.25 MON to FelixStone.").assertExists()
        compose.onNodeWithText("Send Now").assertIsNotEnabled()
    }
}
