package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import app.lensly.ui.home.WalletHistoryScreen
import app.lensly.ui.home.sampleHistory
import app.lensly.ui.home.sampleNetworks
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The History screen, empty and with records. The dates are shown in UTC and English while it is captured, so the goldens are the
 * same on every machine. Record with :app:recordRoborazziDebug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var zone: TimeZone? = null
    private var locale: Locale? = null

    @Before
    fun fixZoneAndLocale() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    @Test
    fun theHistoryOfAWalletWithNoRecords() {
        compose.snapshot("wallet_history_empty") {
            WalletHistoryScreen(
                walletName = "Seed wallet",
                records = emptyList(),
                networkOf = sampleNetworks,
                onBack = {},
            )
        }
    }

    @Test
    fun theHistoryOfAWalletWithSignedRefusedAndCancelledRecords() {
        compose.snapshot("wallet_history_records") {
            WalletHistoryScreen(
                walletName = "Seed wallet",
                records = sampleHistory(),
                networkOf = sampleNetworks,
                onBack = {},
            )
        }
    }
}
