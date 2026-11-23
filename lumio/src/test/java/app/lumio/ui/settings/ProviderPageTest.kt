package app.lumio.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Provider page's key entry: a typed key is saved once the owner finishes it, a saved key shows only its last four characters, and Remove clears it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class ProviderPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<String>()

    private fun show(savedTail: String? = null) {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    ProviderPage(
                    name = "Blockscout",
                    status = "Working",
                    statusNote = null,
                    tags = emptyList(),
                    keyed = true,
                    savedTail = savedTail,
                    offline = null,
                    onSaveKey = { saved += it },
                    onBack = {},
                    onLock = {},
                )
                }
            }
        }
    }

    @Test
    fun aTypedKeyIsSavedTrimmedWhenTheOwnerPressesDone() {
        show()

        compose.onNodeWithTag("api-key").performTextReplacement("  7f3a9c12  ")
        compose.onNodeWithTag("api-key").performImeAction()

        assertEquals(listOf("7f3a9c12"), saved)
    }

    @Test
    fun aSavedKeyShowsOnlyItsLastFourCharactersAndRemoveClearsIt() {
        show(savedTail = "7f3a")

        compose.onNodeWithText("Saved · ends in 7f3a").assertExists()
        compose.onNodeWithText("Remove").performClick()

        assertEquals(listOf(""), saved)
    }
}
