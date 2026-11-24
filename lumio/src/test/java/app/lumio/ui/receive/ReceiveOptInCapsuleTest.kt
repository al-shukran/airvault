package app.lumio.ui.receive

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveNetwork
import app.lumio.ui.home.AssetIconSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * The capsule of the Receive sheet for an Algorand asset: Copy Address while the opt-in read runs, when it fails, and once the account has opted in; Opt In
 * First when the account is confirmed not to have opted in, and a tap on it opens the opt-in of that asset.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiveOptInCapsuleTest {
    @get:Rule
    val compose = createComposeRule()

    private val optIns = mutableListOf<ULong>()

    private val account = ReceiveAccount(name = "Account 1", label = null, group = null, address = "A".repeat(58))
    private val network = ReceiveNetwork(name = "Algorand Testnet", icon = AssetIconSpec.Letter("A"), accounts = listOf(account))
    private val usdc = ReceiveCoin(
        symbol = "USDC",
        name = "USDC",
        subtitle = "",
        held = false,
        icon = AssetIconSpec.Letter("U"),
        networks = listOf(network),
        algorandAssetId = USDC_ID,
    )

    private fun show(optedIn: Boolean?) {
        compose.setContent {
            ClearAirTheme {
                ReceiveFlowSheet(
                    visible = true,
                    opening = 0,
                    start = ReceiveAnswers(coin = usdc, network = network, account = account),
                    assets = emptyList(),
                    optedIn = optedIn,
                    onOptIn = { optIns.add(it) },
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun aReadThatIsRunningShowsCopyAddress() {
        show(optedIn = null)

        compose.onNodeWithText("Copy Address").assertIsDisplayed()
        compose.onNodeWithText("Opt In First").assertDoesNotExist()
    }

    @Test
    fun anAccountThatHasOptedInShowsCopyAddress() {
        show(optedIn = true)

        compose.onNodeWithText("Copy Address").assertIsDisplayed()
        compose.onNodeWithText("Opt In First").assertDoesNotExist()
    }

    @Test
    fun anAccountConfirmedNotOptedInShowsOptInFirstAndOpensTheOptIn() {
        show(optedIn = false)

        compose.onNodeWithText("Copy Address").assertDoesNotExist()
        compose.onNodeWithText("Opt In First").performClick()
        assertEquals(listOf(USDC_ID), optIns)
    }

    private companion object {
        const val USDC_ID = 10458941UL
    }
}
