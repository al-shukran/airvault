package app.lumio.ui.tokens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.tokens.Token
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Add Token screen's behaviour: what each control reports back, and what the screen shows for each state. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class AddTokenScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val dai = Token(1L, "0x6b175474e89094c44da98b954eedeac495271d0f", "DAI", "Dai Stablecoin", 18)

    /** A token a lookup might find: on neither list. */
    private val pepe = Token(1L, "0x6982508145454ce325ddbe47a25d4ec3d2311933", "PEPE", "Pepe", 18)

    private var address by mutableStateOf("")
    private var looked = 0
    private var added: Token? = null
    private var removed: Token? = null
    private var cancelled = 0

    private fun show(
        defaults: List<Token> = listOf(usdc),
        custom: List<Token> = listOf(dai),
        found: Token? = null,
        problem: String? = null,
        busy: Boolean = false,
    ) {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    AddTokenScreen(
                    chainName = "Ethereum",
                    defaults = defaults,
                    custom = custom,
                    address = address,
                    busy = busy,
                    found = found,
                    problem = problem,
                    offline = null,
                    onAddress = { address = it },
                    onLookUp = { looked++ },
                    onAdd = { added = it },
                    onCancel = { cancelled++ },
                    onRemove = { removed = it },
                    onBack = {},
                    onLock = {},
                )
                }
            }
        }
    }

    @Test
    fun theTokensOfTheChainAreListedInTheirTwoGroups() {
        show()

        compose.onNodeWithText("Default Tokens", ignoreCase = true).assertExists()
        compose.onNodeWithText("Your Tokens", ignoreCase = true).assertExists()
        compose.onNodeWithText("USDC").assertExists()
        compose.onNodeWithText("DAI").assertExists()
    }

    @Test
    fun onlyTheOwnersTokensHaveARemoveAction() {
        show()

        compose.onAllNodesWithText("Remove", ignoreCase = true).assertCountEquals(1)
        compose.onNodeWithText("Remove", ignoreCase = true).performClick()
        assertEquals(dai, removed)
    }

    @Test
    fun leavingTheAddressLooksItUp() {
        show()

        compose.onNodeWithTag("token-address").performTextReplacement("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
        compose.onNodeWithTag("token-address").performImeAction()

        assertEquals(1, looked)
    }

    @Test
    fun aFoundTokenShowsItsDetailsAndAddOrCancelReportBack() {
        show(found = pepe)

        compose.onNodeWithTag("token-found").assertTextContains("Found on Ethereum", substring = true)
        compose.onNodeWithText("Pepe").assertExists()
        compose.onNodeWithTag("token-add").performClick()
        compose.onNodeWithText("Cancel", ignoreCase = true).performClick()

        assertEquals(pepe, added)
        assertEquals(1, cancelled)
    }

    @Test
    fun aRefusalIsShownInItsOwnWords() {
        show(problem = "No token found at this address on Ethereum.")

        compose.onNodeWithText("No token found at this address on Ethereum.", substring = true).assertExists()
    }

    @Test
    fun whileALookupRunsTheFieldAndButtonsAreHeld() {
        show(busy = true)

        compose.onNodeWithTag("token-paste").assertIsNotEnabled()
    }

    @Test
    fun pasteFillsTheAddressFromTheClipboard() {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("address", "0x6b17 5474 e890 94c4 4da9 8b95 4eed eac4 9527 1d0f"))
        show()

        compose.onNodeWithTag("token-paste").performClick()

        // The pasted groups are read without their spaces, and the field shows them in groups of four.
        assertEquals("0x6b175474e89094c44da98b954eedeac495271d0f", address)
        compose.onNodeWithTag("token-address").assertTextContains("0x6b17 5474 e890 94c4 4da9 8b95 4eed eac4 9527 1d0f")
        assertNull(added)
    }
}
