package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.send.algorand.AddAssetRows
import app.lumio.send.algorand.AlgorandAddAssetScreen
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Add asset screen as goldens: the verified list not yet added, the same asset once added, and an asset ID the owner typed. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
class AlgorandAddAssetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val usdc = 10458941UL

    @Test
    fun algorandAddAssetList() = compose.lumioSnapshot("algorand_add_asset_list") {
        AlgorandAddAssetScreen(
            rows = AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), ""),
            query = "",
            reading = false,
            onQuery = {},
            onPick = {},
            onBack = {},
        )
    }

    @Test
    fun algorandAddAssetAdded() = compose.lumioSnapshot("algorand_add_asset_added") {
        AlgorandAddAssetScreen(
            rows = AddAssetRows.rows(ALGORAND_TESTNET, listOf(AsaHolding(usdc, BigInteger.ZERO, false)), ""),
            query = "",
            reading = false,
            onQuery = {},
            onPick = {},
            onBack = {},
        )
    }

    @Test
    fun algorandAddAssetTypedId() = compose.lumioSnapshot("algorand_add_asset_typed_id") {
        AlgorandAddAssetScreen(
            rows = AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "12345"),
            query = "12345",
            reading = false,
            onQuery = {},
            onPick = {},
            onBack = {},
        )
    }
}
