package app.lumio.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.Fiat
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.FiatRate
import app.lumio.data.config.ThemeMode
import app.lumio.ui.kit.LocalFiat
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Display settings: the two switches show their values, and a change is reported at once. There is no minimum to enter. */
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsSectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var hidden: Boolean? = null
    private var theme: ThemeMode? = null
    private var currency: FiatCurrency? = null
    private var opened: String? = null

    private fun show(settings: DisplaySettings = DisplaySettings(), notice: String? = null, fiat: Fiat = Fiat.USD) {
        compose.setContent {
            ClearAirHarness {
                CompositionLocalProvider(LocalFiat provides fiat) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DisplaySettingsSection(
                            settings = settings,
                            notice = notice,
                            onHideBalances = { hidden = it },
                            onOpenCurrency = { opened = "Currency" },
                            onOpenTheme = { opened = "Theme" },
                            onSmallBalance = {},
                        )
                    }
                }
            }
        }
    }

    private fun showPage() {
        compose.setContent {
            ClearAirHarness {
                CompositionLocalProvider(LocalFiat provides Fiat.USD) {
                    DisplaySettingsPage(
                        settings = DisplaySettings(),
                        notice = null,
                        onHideBalances = {},
                        onCurrency = { currency = it },
                        onTheme = { theme = it },
                        onSmallBalance = {},
                        offline = null,
                        onBack = {},
                        onLock = {},
                    )
                }
            }
        }
    }

    @Test
    fun theSmallBalanceThresholdIsWrittenInTheChosenCurrency() {
        show(DisplaySettings(currency = FiatCurrency.EUR, smallBalanceUsd = 1.0), fiat = Fiat.of(FiatCurrency.EUR, FiatRate(FiatCurrency.EUR, 0.5)))

        compose.onNodeWithText("€0.50").assertExists()
    }

    @Test
    fun theSmallBalanceThresholdIsInDollarsWhileNoRateIsKnown() {
        show(DisplaySettings(currency = FiatCurrency.EUR, smallBalanceUsd = 1.0), fiat = Fiat.of(FiatCurrency.EUR, null))

        compose.onNodeWithText("\$1.00").assertExists()
    }

    @Test
    fun theRowsAreHideBalancesCurrencyAndThemeWithTheirValues() {
        show()

        compose.onNodeWithText("Hide Balances").assertExists()
        compose.onNodeWithText("Currency").assertExists()
        compose.onNodeWithText("USD").assertExists()
        compose.onNodeWithText("Theme").assertExists()
        compose.onNodeWithText("Follow System").assertExists()
    }

    @Test
    fun theSwitchReportsItsNewValue() {
        show()

        compose.onNodeWithText("Hide Balances").performClick()

        assertEquals(true, hidden)
    }

    @Test
    fun theCurrencyAndThemeRowsAskThePageToOpenTheirSheets() {
        show()

        compose.onNodeWithText("Currency").performClick()
        assertEquals("Currency", opened)
        compose.onNodeWithText("Theme").performClick()
        assertEquals("Theme", opened)
    }

    @Test
    fun aThemeChoiceAppliesAndIsReported() {
        showPage()

        compose.onNodeWithText("Theme").performClick()
        // The sheet is over the page, so its row is tapped where it shows.
        compose.onNodeWithText("Dark").performClick()

        assertEquals(ThemeMode.DARK, theme)
    }

    @Test
    fun aCurrencyChoiceAppliesAndIsReported() {
        showPage()

        compose.onNodeWithText("Currency").performClick()
        compose.onNodeWithText("Euro").performClick()

        assertEquals(FiatCurrency.EUR, currency)
    }

    @Test
    fun backClosesTheSheetWithoutChoosing() {
        showPage()

        compose.onNodeWithText("Theme").performClick()
        compose.onNodeWithText("Dark").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("Dark").assertDoesNotExist()
        assertEquals(null, theme)
    }
}

/** The theme a settings page is drawn in in the app: the Clear Air theme, under the Lumen theme that wraps Lumio. */
@androidx.compose.runtime.Composable
private fun ClearAirHarness(content: @androidx.compose.runtime.Composable () -> Unit) {
    LumenTheme { app.lensly.ui.theme.ClearAirTheme(content = content) }
}
