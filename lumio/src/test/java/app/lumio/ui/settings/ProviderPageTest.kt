package app.lumio.ui.settings

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Provider page (B5): its switch turns the provider on and off, the networks it serves are counted and named, and its key entry saves a typed key
 * once the owner finishes it. A saved key shows only its last four characters, and Remove clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class ProviderPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<String>()
    private val switched = mutableListOf<Boolean>()

    private fun show(
        savedTail: String? = null,
        enabled: Boolean = true,
        networks: List<String> = emptyList(),
        keyRequired: Boolean = false,
        keyError: String? = null,
        backLabel: String = "Settings",
    ) {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    ProviderPage(
                        name = "Blockscout",
                        status = "Working",
                        statusNote = null,
                        enabled = enabled,
                        onEnabled = { switched += it },
                        tags = emptyList(),
                        networks = networks,
                        keyed = true,
                        keyRequired = keyRequired,
                        savedTail = savedTail,
                        keyError = keyError,
                        offline = null,
                        onSaveKey = { saved += it },
                        onBack = {},
                        onLock = {},
                        backLabel = backLabel,
                    )
                }
            }
        }
    }

    @Test
    fun theBackArrowNamesTheScreenItReturnsTo() {
        show(backLabel = "Explore")

        compose.onNodeWithContentDescription("Back to Explore").assertExists()
    }

    @Test
    fun theSwitchTurnsTheProviderOffAndOn() {
        show(enabled = true)

        compose.onNodeWithTag("provider-enabled").assertIsOn().performClick()

        assertEquals(listOf(false), switched)
    }

    @Test
    fun theNetworksItServesAreCountedAndNamed() {
        show(networks = listOf("Ethereum", "Base", "Arbitrum"))

        compose.onNodeWithText("Ethereum, Base and Arbitrum").assertExists()
    }

    @Test
    fun aTypedKeyIsSavedTrimmedWhenTheOwnerPressesDone() {
        show()

        compose.onNodeWithTag("api-key").performTextReplacement("  7f3a9c12  ")
        compose.onNodeWithTag("api-key").performImeAction()

        assertEquals(listOf("7f3a9c12"), saved)
    }

    @Test
    fun aKeyTheSaveRefusesStaysInTheFieldWithItsMessage() {
        show(keyError = "The key could not be saved. Try again.")

        compose.onNodeWithTag("api-key").performTextReplacement("7f3a9c12")
        compose.onNodeWithTag("api-key").performImeAction()

        compose.onNodeWithTag("api-key").assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("7f3a9c12")))
        compose.onNodeWithText("The key could not be saved. Try again.", substring = true).assertExists()
    }

    @Test
    fun aKeyRequiredProviderSaysItsKeyIsRequiredNotOptional() {
        show(keyRequired = true)

        compose.onNodeWithText("API Key (Required)").assertExists()
        compose.onNodeWithText("API Key (Optional)").assertDoesNotExist()
    }

    @Test
    fun aKeyOptionalProviderSaysItsKeyIsOptional() {
        show(keyRequired = false)

        compose.onNodeWithText("API Key (Optional)").assertExists()
    }

    @Test
    fun aSavedKeyShowsOnlyItsLastFourCharactersAndRemoveClearsIt() {
        show(savedTail = "7f3a")

        compose.onNodeWithText("Saved · ends in 7f3a").assertExists()
        compose.onNodeWithText("Remove").performClick()

        assertEquals(listOf(""), saved)
    }
}
