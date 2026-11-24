package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.Fiat
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.FiatRate
import app.lumio.ui.kit.LocalFiat
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Display settings: the two switches show their values, and a change is reported at once. There is no minimum to enter. */
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var showTotal: Boolean? = null
    private var showAssets: Boolean? = null

    private fun show(settings: DisplaySettings = DisplaySettings(), notice: String? = null, fiat: Fiat = Fiat.USD) {
        compose.setContent {
            ClearAirHarness {
                CompositionLocalProvider(LocalFiat provides fiat) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        DisplaySettingsSection(
                            settings = settings,
                            notice = notice,
                            onShowTotal = { showTotal = it },
                            onShowAssets = { showAssets = it },
                            onCurrency = {},
                            onSmallBalance = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theSmallBalanceThresholdIsWrittenInTheChosenCurrency() {
        show(DisplaySettings(currency = FiatCurrency.EUR, smallBalanceUsd = 1.0), fiat = Fiat.of(FiatCurrency.EUR, FiatRate(FiatCurrency.EUR, 0.5)))

        compose.onNodeWithText("HIDE SMALL BALANCES · €0.50").assertExists()
    }

    @Test
    fun theSmallBalanceThresholdIsInDollarsWhileNoRateIsKnown() {
        show(DisplaySettings(currency = FiatCurrency.EUR, smallBalanceUsd = 1.0), fiat = Fiat.of(FiatCurrency.EUR, null))

        compose.onNodeWithText("HIDE SMALL BALANCES · \$1.00").assertExists()
    }

    @Test
    fun theSwitchesShowTheirValuesAndNoMinimumIsAsked() {
        show()

        compose.onNodeWithText("Show Total Balance").assertExists()
        compose.onNodeWithText("Show Individual Asset Balances").assertExists()
        compose.onNodeWithText("Minimum balance (USD)").assertDoesNotExist()
    }

    @Test
    fun aSwitchReportsItsNewValue() {
        show()

        compose.onNodeWithText("Show Total Balance").performClick()

        assertEquals(true, showTotal)
        assertNull(showAssets)
    }
}

/** The theme a settings page is drawn in in the app: the Clear Air theme, under the Lumen theme that wraps Lumio. */
@androidx.compose.runtime.Composable
private fun ClearAirHarness(content: @androidx.compose.runtime.Composable () -> Unit) {
    LumenTheme { app.lensly.ui.theme.ClearAirTheme(content = content) }
}
