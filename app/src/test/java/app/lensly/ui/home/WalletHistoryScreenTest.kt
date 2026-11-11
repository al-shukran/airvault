package app.lensly.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.sign.ReviewRecord
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.sign.groupAddress
import app.lensly.ui.theme.LenslyTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The History screen: its empty state, its days and rows, and the sheet that opens every fact of a record. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class WalletHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(records: List<ReviewRecord>) {
        compose.setContent {
            LenslyTheme {
                WalletHistoryScreen(
                    walletName = "Seed wallet",
                    records = records,
                    networkOf = sampleNetworks,
                    onBack = {},
                )
            }
        }
    }

    /** The day heading of [atMs], as the screen writes it: the date in the default locale, in capitals. */
    private fun dayHeading(atMs: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))
            .uppercase()

    @Test
    fun anEmptyHistorySaysWhatWillBeListedHere() {
        show(emptyList())

        compose.onNodeWithText("NO REVIEWS YET").assertIsDisplayed()
    }

    @Test
    fun theRecordsAreListedUnderTheirDaysNewestFirst() {
        val records = sampleHistory()
        show(records)

        compose.onNodeWithText(dayHeading(records.first().atMs)).assertIsDisplayed()
        compose.onNodeWithText(dayHeading(records.last().atMs)).assertIsDisplayed()
        compose.onNodeWithText("transfer").assertIsDisplayed()
        compose.onNodeWithText("Unknown contract call").assertIsDisplayed()
        compose.onNodeWithText("Sign message").assertIsDisplayed()
        compose.onNodeWithText("1.5 USDC").assertIsDisplayed()
    }

    @Test
    fun aTapOpensEveryRecordedFactWithEveryAddressInFull() {
        show(sampleHistory())

        compose.onNodeWithText("transfer").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Signed").assertIsDisplayed()
        compose.onNodeWithText("Monad Testnet · chain 10143").assertIsDisplayed()
        compose.onNodeWithText(groupAddress(SAMPLE_ACCOUNT)).assertIsDisplayed()
        compose.onNodeWithText(groupAddress(SAMPLE_RECIPIENT)).assertIsDisplayed()
        compose.onNodeWithText("DATA HASH, SHA-256").assertIsDisplayed()
    }
}
