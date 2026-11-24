package app.lumio.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Network page (B4): the built-in RPC returns to it at once, Add checks the typed address, and a refusal shows under the field. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class NetworkPageTest {

    @get:Rule
    val compose = createComposeRule()

    private var builtIn = 0
    private val added = mutableListOf<String>()

    private fun show(custom: String? = null, notice: String? = null) {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    NetworkPage(
                    chainName = "Base",
                    chainLine = "Chain 8453 · ETH",
                    custom = custom,
                    busy = false,
                    notice = notice,
                    offline = null,
                    onUseBuiltIn = { builtIn++ },
                    onAddCustom = { added += it },
                    onBack = {},
                    onLock = {},
                )
                }
            }
        }
    }

    @Test
    fun theBuiltInRowReturnsToItWhenACustomAddressIsInUse() {
        show(custom = "https://base.example.org")

        compose.onNodeWithText("Built-in RPC").performClick()

        assertEquals(1, builtIn)
    }

    @Test
    fun addChecksTheTrimmedTypedAddress() {
        show()

        compose.onNodeWithTag("custom-rpc").performTextReplacement("  https://base.example.org ")
        compose.onNodeWithText("Add").performClick()

        assertEquals(listOf("https://base.example.org"), added)
    }

    @Test
    fun aRefusalShowsUnderTheField() {
        show(notice = "This isn't a valid https address")

        compose.onNodeWithText("This isn't a valid https address", substring = true).assertExists()
    }
}
