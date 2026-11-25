package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
    val compose = createComposeRule()

    private var hidden: Boolean? = null
    private var theme: ThemeMode? = null

    private fun show(settings: DisplaySettings = DisplaySettings(), notice: String? = null, fiat: Fiat = Fiat.USD) {
        compose.setContent {
            ClearAirHarness {
                CompositionLocalProvider(LocalFiat provides fiat) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DisplaySettingsSection(
                            settings = settings,
                            notice = notice,
                            onHideBalances = { hidden = it },
                            onCurrency = {},
                            onTheme = { theme = it },
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
    fun aThemeChoiceAppliesAndIsReported() {
        show()

        compose.onNodeWithText("Theme").performClick()
        // The sheet sits in the page column here, below the visible window, so the row is chosen by its action, not by a tap.
        compose.onNodeWithText("Dark").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(ThemeMode.DARK, theme)
    }
}

/** The theme a settings page is drawn in in the app: the Clear Air theme, under the Lumen theme that wraps Lumio. */
@androidx.compose.runtime.Composable
private fun ClearAirHarness(content: @androidx.compose.runtime.Composable () -> Unit) {
    LumenTheme { app.lensly.ui.theme.ClearAirTheme(content = content) }
}
