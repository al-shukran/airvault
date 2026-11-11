package app.lensly.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.ReviewHistory
import app.lensly.app.WalletReviewRecord
import app.lensly.app.WalletStore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Activity screen: its empty state, the records of two wallets in one list, the filter chips, and the records the data
 * function returns for a decoy session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ActivityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"

    private fun showActivity(records: List<WalletReviewRecord> = sampleActivity()) {
        compose.setContent {
            LenslyTheme {
                ActivityScreen(
                    records = records,
                    wallets = sampleWallets(),
                    profiles = sampleActivityProfiles(),
                    networkOf = sampleNetworks,
                    onBack = {},
                )
            }
        }
    }

    /** Where the row of [text] starts on the screen, so the order of two rows can be compared. */
    private fun topOf(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun withNoRecordTheScreenSaysWhatWillBeListedHere() {
        showActivity(emptyList())

        compose.onNodeWithText("NO ACTIVITY YET").assertIsDisplayed()
    }

    @Test
    fun theRecordsOfTwoWalletsAreOneListNewestFirst() {
        showActivity()

        compose.onNodeWithText("transfer").assertIsDisplayed()
        compose.onNodeWithText("Unknown contract call").assertIsDisplayed()
        compose.onNodeWithText("Sign message").assertIsDisplayed()
        // Each row names its wallet, so the two wallets can be told apart in the one list.
        compose.onAllNodesWithText("Seed wallet").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Trading").assertIsDisplayed()

        assertTrue(topOf("transfer") < topOf("Unknown contract call"), "the newer record is listed first")
        assertTrue(topOf("Unknown contract call") < topOf("Sign message"), "the older record is listed last")
    }

    @Test
    fun aFilterChipKeepsOnlyItsOutcome() {
        showActivity()
        compose.onNodeWithText("transfer").assertIsDisplayed()

        compose.onNodeWithText("REFUSED").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Unknown contract call").assertIsDisplayed()
        compose.onNodeWithText("transfer").assertDoesNotExist()
        compose.onNodeWithText("Sign message").assertDoesNotExist()
    }

    @Test
    fun theDataFunctionOfADecoySessionHoldsOnlyItsOwnRecords() {
        val layout = VaultLayout(tmp.root)
        val real = VaultSession(layout, ByteArray(32) { (it + 5).toByte() })
        try {
            val realWallet = WalletStore(real).create(
                name = "REAL",
                deviceGenerated = false,
                words = words,
                passphrase = null,
                nowMs = 1L,
            )
            ReviewHistory(real).append(realWallet, sampleHistory()[0])

            val decoy = VaultSession(layout, ByteArray(32) { 99 }, isDecoy = true)
            try {
                val decoyWallet = WalletStore(decoy).create(
                    name = "Everyday",
                    deviceGenerated = true,
                    words = words,
                    passphrase = null,
                    nowMs = 2L,
                )
                ReviewHistory(decoy).append(decoyWallet, sampleHistory()[1])

                val listed = ReviewHistory(decoy).listAll()

                assertEquals(listOf(decoyWallet), listed.map { it.walletId }.distinct())
                assertTrue(listed.none { it.walletId == realWallet }, "the decoy session lists no real wallet")
            } finally {
                decoy.close()
            }
        } finally {
            real.close()
        }
    }
}
