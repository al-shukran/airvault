package app.lumio.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import app.lumio.data.config.AssetSort
import app.lumio.data.config.AssetSortKey
import app.lumio.data.config.DisplaySettings
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.home.AccountOption
import app.lumio.home.HomeAsset
import app.lumio.home.Masking
import app.lumio.home.NativeAsset
import app.lumio.home.PickTarget
import app.lumio.network.NetworkMode
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lumio's Home: the total and its eye, the sort sheet that saves the order, the list above the minimum, the rows, the pickers of Send and Receive,
 * the side, the lock, and the notices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class LumioHomeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTotalIsMaskedUntilTheOwnerRevealsItWithoutChangingTheSetting() {
        var saved: DisplaySettings? = null
        setHome(onSaved = { saved = it })

        compose.onNodeWithTag(HOME_TOTAL_TAG).assertTextEquals(Masking.MASK)
        compose.onNodeWithTag(HOME_REVEAL_TAG).performClick()
        compose.onNodeWithTag(HOME_TOTAL_TAG).assertTextEquals("\$1,234.56")
        compose.onNodeWithTag(HOME_REVEAL_TAG).performClick()
        compose.onNodeWithTag(HOME_TOTAL_TAG).assertTextEquals(Masking.MASK)
        assertEquals(null, saved)
    }

    @Test
    fun theTotalShowsWhenTheSettingIsOnAndTheEyeIsGone() {
        setHome(settings = DisplaySettings(showTotal = true))

        compose.onNodeWithTag(HOME_TOTAL_TAG).assertTextEquals("\$1,234.56")
        compose.onNodeWithTag(HOME_REVEAL_TAG).assertDoesNotExist()
    }

    @Test
    fun aTotalWithNoPriceIsADashAndSaysSo() {
        setHome(assets = listOf(testnetCoin()), settings = DisplaySettings(showTotal = true))

        compose.onNodeWithTag(HOME_TOTAL_TAG).assertTextEquals("—")
        compose.onNodeWithText("No price yet").assertExists()
    }

    @Test
    fun assetsBelowTheMinimumAreHiddenAndAnUnpricedAssetIsNot() {
        setHome(assets = listOf(ethereum(), dust(), testnetCoin()))

        compose.onNodeWithTag(assetRowTag("EVM-1")).assertExists()
        compose.onNodeWithTag(assetRowTag("EVM-10")).assertDoesNotExist()
        compose.onNodeWithTag(assetRowTag("EVM-11155111")).assertExists()
    }

    @Test
    fun aRowShowsItsBalanceItsValueItsChangeAndTheTestnetBadge() {
        setHome(assets = listOf(ethereum(change = 1.25), testnetCoin()))

        compose.onNodeWithTag(assetRowTag("EVM-1")).assertTextContains("1.5")
        compose.onNodeWithTag(assetRowTag("EVM-1")).assertTextContains("\$1,234.56")
        compose.onNodeWithTag(assetRowTag("EVM-1")).assertTextContains("+1.25%")
        compose.onAllNodesWithText("TESTNET", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theSortSheetSavesTheKeyAndThenTheDirection() {
        var saved: DisplaySettings? = null
        setHome(onSaved = { saved = it })

        compose.onNodeWithTag(HOME_SORT_TAG).performClick()
        compose.onNodeWithText("Name").performClick()
        compose.onNodeWithText("Ascending").performClick()

        assertEquals(AssetSort(AssetSortKey.NAME, descending = false), saved?.sort)
    }

    @Test
    fun aTapOnAnAssetOpensItsPage() {
        var opened: HomeAsset? = null
        setHome(onAsset = { opened = it })

        compose.onNodeWithTag(assetRowTag("EVM-1")).performClick()

        assertEquals("EVM-1", opened?.id)
    }

    @Test
    fun withNoWalletTheHomeSaysSoAndOffersPairing() {
        var paired = false
        setHome(assets = emptyList(), hasWallets = false, onPair = { paired = true })

        compose.onNodeWithText("No wallets paired yet", ignoreCase = true).assertExists()
        compose.onNodeWithText("Pair from Lensly", ignoreCase = true).performClick()

        assertTrue(paired)
    }

    @Test
    fun sendOpensThePickerAndAPickStartsThatSend() {
        var picked: AccountOption? = null
        val option = AccountOption(PickTarget.Evm("w1"), "Ethereum", "Demo")
        setHome(sendOptions = listOf(option), onSend = { picked = it })

        compose.onNodeWithTag(HOME_SEND_TAG).performClick()
        compose.onNodeWithTag(pickRowTag(0)).performClick()

        assertEquals(option, picked)
    }

    @Test
    fun receiveOpensThePickerWithItsOwnOptions() {
        var picked: AccountOption? = null
        val option = AccountOption(PickTarget.BitcoinAccount("w1_MAINNET_TAPROOT"), "Bitcoin taproot", "Demo")
        setHome(receiveOptions = listOf(option), onReceive = { picked = it })

        compose.onNodeWithTag(HOME_RECEIVE_TAG).performClick()
        compose.onNodeWithText("Bitcoin taproot").assertExists()
        compose.onNodeWithTag(pickRowTag(0)).performClick()

        assertEquals(option, picked)
    }

    @Test
    fun theLockLocksAndTheSideSwitchesFromHome() {
        var locked = false
        var side: NetworkMode? = null
        setHome(onLock = { locked = true }, onMode = { side = it })

        compose.onNodeWithContentDescription("Lock the vault").performClick()
        compose.onNodeWithText("Testnet").performClick()

        assertTrue(locked)
        assertEquals(NetworkMode.TESTNET, side)
    }

    @Test
    fun hiddenBalancesAreMaskedWhenTheSettingForAssetsIsOff() {
        setHome(settings = DisplaySettings(showAssets = false))

        compose.onNodeWithTag(assetRowTag("EVM-1")).assertTextContains(Masking.MASK)
    }

    @Test
    fun aKeptPriceSaysWhenItWasRead() {
        setHome(staleSinceMs = STALE_SINCE_MS)

        compose.onNodeWithText("Last known prices from", substring = true).assertExists()
    }

    @Test
    fun aRefreshThatDidNotAnswerSaysSo() {
        setHome(notice = "Some balances could not be refreshed. The last known balances are shown.")

        compose.onNodeWithText("Some balances could not be refreshed", substring = true).assertExists()
    }

    @Test
    fun theTestnetSideMarksItsCoinsAsTestnet() {
        setHome(mode = NetworkMode.TESTNET, assets = listOf(testnetCoin()))

        compose.onAllNodesWithText("TESTNET", useUnmergedTree = true).assertCountEquals(1)
    }

    private fun ethereum(change: Double? = null) = NativeAsset(
        id = "EVM-1",
        symbol = "ETH",
        name = "Ethereum",
        network = NetworkId(Family.EVM, "1", testnet = false),
        balance = BigDecimal("1.5"),
        usd = 1234.56,
        change24h = change,
    )

    private fun dust() = NativeAsset(
        id = "EVM-10",
        symbol = "ETH",
        name = "Optimism",
        network = NetworkId(Family.EVM, "10", testnet = false),
        balance = BigDecimal("0.0001"),
        usd = 0.5,
    )

    private fun testnetCoin() = NativeAsset(
        id = "EVM-11155111",
        symbol = "ETH",
        name = "Sepolia",
        network = NetworkId(Family.EVM, "11155111", testnet = true),
        balance = BigDecimal("0.25"),
    )

    /**
     * Home with its settings held in state, as the app holds them: a saved change is applied, so the sheet shows the new order. [onSaved] receives the
     * settings after each save.
     */
    private fun setHome(
        assets: List<HomeAsset> = listOf(ethereum()),
        settings: DisplaySettings = DisplaySettings(),
        mode: NetworkMode = NetworkMode.MAINNET,
        hasWallets: Boolean = true,
        staleSinceMs: Long? = null,
        notice: String? = null,
        sendOptions: List<AccountOption> = emptyList(),
        receiveOptions: List<AccountOption> = emptyList(),
        onLock: () -> Unit = {},
        onMode: (NetworkMode) -> Unit = {},
        onAsset: (HomeAsset) -> Unit = {},
        onPair: () -> Unit = {},
        onSend: (AccountOption) -> Unit = {},
        onReceive: (AccountOption) -> Unit = {},
        onSaved: (DisplaySettings) -> Unit = {},
    ) {
        compose.setContent {
            LenslyTheme {
                var current by remember { mutableStateOf(settings) }
                LumioHome(
                    assets = assets,
                    settings = current,
                    mode = mode,
                    onMode = onMode,
                    onUpdateDisplay = { change ->
                        current = change(current)
                        onSaved(current)
                    },
                    onLock = onLock,
                    onAsset = onAsset,
                    onPair = onPair,
                    hasWallets = hasWallets,
                    refreshing = false,
                    onRefresh = {},
                    staleSinceMs = staleSinceMs,
                    notice = notice,
                    sendOptions = sendOptions,
                    receiveOptions = receiveOptions,
                    onSend = onSend,
                    onReceive = onReceive,
                )
            }
        }
    }

    private companion object {
        /** A time before the test's clock, so the stale caption names a date. */
        const val STALE_SINCE_MS = 1_800_000_000_000L
    }
}
