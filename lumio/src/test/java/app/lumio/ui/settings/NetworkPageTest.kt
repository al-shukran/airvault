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

/**
 * The Network page (B4): the built-in RPC returns to it at once, a custom address is selected or removed on its row, Add checks the typed address,
 * and a refusal shows under the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class NetworkPageTest {

    @get:Rule
    val compose = createComposeRule()

    private var builtIn = 0
    private val added = mutableListOf<String>()
    private val selected = mutableListOf<String>()
    private val removed = mutableListOf<String>()

    private fun show(
        custom: List<String> = emptyList(),
        active: String? = null,
        builtInUrl: String? = "https://public.1rpc.io/base",
        inUseLine: String = "",
        notice: String? = null,
    ) {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    NetworkPage(
                        chainName = "Base",
                        chainLine = "Chain 8453 · ETH",
                        builtInUrl = builtInUrl,
                        custom = custom,
                        active = active,
                        inUseLine = inUseLine,
                        providers = emptyList(),
                        busy = false,
                        notice = notice,
                        offline = null,
                        onUseBuiltIn = { builtIn++ },
                        onUseCustom = { selected += it },
                        onRemoveCustom = { removed += it },
                        onAddCustom = { added += it },
                        onReorder = {},
                        onBack = {},
                        onLock = {},
                    )
                }
            }
        }
    }

    @Test
    fun theBuiltInRowReturnsToItWhenACustomAddressIsInUse() {
        show(custom = listOf("https://base.example.org"), active = "https://base.example.org")

        compose.onNodeWithText("https://public.1rpc.io/base").performClick()

        assertEquals(1, builtIn)
    }

    @Test
    fun theBuiltInRowShowsItsAddressAndCannotBeDeleted() {
        show()

        compose.onNodeWithText("https://public.1rpc.io/base").assertExists()
        compose.onNodeWithText("Built In").assertExists()
        compose.onNodeWithText("Can't be deleted").assertExists()
    }

    @Test
    fun theCustomAddressInUseReadsItsStatusAndTheDayItWasSet() {
        show(custom = listOf("https://base.example.org"), active = "https://base.example.org", inUseLine = "✓ Working · in use since 19 Nov")

        compose.onNodeWithText("✓ Working · in use since 19 Nov").assertExists()
    }

    @Test
    fun aCustomAddressIsSelectedByItsRow() {
        show(custom = listOf("https://base.example.org", "https://base-2.example.org"), active = "https://base.example.org")

        compose.onNodeWithText("https://base-2.example.org").performClick()

        assertEquals(listOf("https://base-2.example.org"), selected)
    }

    @Test
    fun aCustomAddressIsRemovedByItsRemoveAction() {
        show(custom = listOf("https://base.example.org"), active = null)

        compose.onNodeWithText("Remove").performClick()

        assertEquals(listOf("https://base.example.org"), removed)
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
