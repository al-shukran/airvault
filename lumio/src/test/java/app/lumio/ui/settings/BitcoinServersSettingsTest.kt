package app.lumio.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.lensly.core.btc.BtcNetwork
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.btc.EsploraServers
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Bitcoin servers: a valid server is applied as the owner finishes, an invalid one is refused under its field, and Reset puts both back. */
@RunWith(RobolectricTestRunner::class)
class BitcoinServersSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val applied = mutableListOf<Pair<BtcNetwork, String?>>()
    private val indexers = mutableListOf<String?>()
    private var resets = 0
    private val custom = "https://node.example.org/api"
    private val indexerUrl = "https://ord.example.org"

    private fun show(mainnet: String = EsploraServers.MAINNET_DEFAULT, indexer: String = "") {
        compose.setContent {
            ClearAirTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    BitcoinServersSettings(
                        mainnet = mainnet,
                        testnet4 = EsploraServers.TESTNET4_DEFAULT,
                        indexer = indexer,
                        error = null,
                        onApply = { network, url -> applied += network to url },
                        onIndexer = { indexers += it },
                        onReset = { resets++ },
                    )
                }
            }
        }
    }

    @Test
    fun aValidServerIsAppliedWhenTheOwnerPressesTheKeyboardAction() {
        show()

        compose.onNodeWithTag(serverTag(BtcNetwork.MAINNET)).performTextReplacement(custom)
        compose.onNodeWithTag(serverTag(BtcNetwork.MAINNET)).performImeAction()

        assertEquals(listOf<Pair<BtcNetwork, String?>>(BtcNetwork.MAINNET to custom), applied)
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefusedUnderItsFieldAndAppliesNothing() {
        show()

        compose.onNodeWithTag(serverTag(BtcNetwork.MAINNET)).performTextReplacement("http://plain.example.org")
        compose.onNodeWithTag(serverTag(BtcNetwork.MAINNET)).performImeAction()

        assertEquals(emptyList<Pair<BtcNetwork, String?>>(), applied)
        compose.onNodeWithText("The address must start with https://.", substring = true).assertExists()
    }

    @Test
    fun aBlankFieldAppliesThePublicServerAsNoOverride() {
        show(mainnet = custom)

        compose.onNodeWithTag(serverTag(BtcNetwork.MAINNET)).performTextReplacement("")
        compose.onNodeWithTag(serverTag(BtcNetwork.MAINNET)).performImeAction()

        assertEquals(listOf<Pair<BtcNetwork, String?>>(BtcNetwork.MAINNET to null), applied)
    }

    @Test
    fun resetPutsTheFieldBackOnThePublicServer() {
        show(mainnet = custom)

        compose.onNodeWithText("Reset to Defaults").performScrollTo().performClick()

        assertEquals(1, resets)
        compose.onNodeWithText(EsploraServers.MAINNET_DEFAULT).assertExists()
    }

    @Test
    fun theIndexerIsOffUntilTheOwnerSetsOne() {
        show()

        compose.onNodeWithTag(INDEXER_TAG).assertExists()
        compose.onNodeWithText("Off. Lumio checks no output for inscriptions or runes.").assertExists()
    }

    @Test
    fun aValidIndexerAddressIsAppliedWhenTheOwnerPressesTheKeyboardAction() {
        show()

        compose.onNodeWithTag(INDEXER_TAG).performTextReplacement(indexerUrl)
        compose.onNodeWithTag(INDEXER_TAG).performImeAction()

        assertEquals(listOf<String?>(indexerUrl), indexers)
    }

    @Test
    fun anIndexerAddressThatIsNotHttpsIsRefusedUnderItsFieldAndAppliesNothing() {
        show()

        compose.onNodeWithTag(INDEXER_TAG).performTextReplacement("http://plain.example.org")
        compose.onNodeWithTag(INDEXER_TAG).performImeAction()

        assertEquals(emptyList<String?>(), indexers)
        compose.onNodeWithText("The address must start with https://.", substring = true).assertExists()
    }

    @Test
    fun aBlankIndexerTurnsItOff() {
        show(indexer = indexerUrl)

        compose.onNodeWithTag(INDEXER_TAG).performTextReplacement("")
        compose.onNodeWithTag(INDEXER_TAG).performImeAction()

        assertEquals(listOf<String?>(null), indexers)
    }
}
