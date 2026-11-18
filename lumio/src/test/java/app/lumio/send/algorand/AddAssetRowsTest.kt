package app.lumio.send.algorand

import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Test

/** The rows of the Add asset list: the verified assets of the network, the search over them, a typed asset ID, and the assets already added. */
class AddAssetRowsTest {
    private val testnetUsdc = 10458941UL
    private val mainnetUsdc = 31566704UL

    @Test
    fun anEmptySearchListsTheVerifiedAssetOfTheNetworkThatIsNotAddedYet() {
        val testnet = AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "")
        val mainnet = AddAssetRows.rows(ALGORAND_MAINNET, emptyList(), "")

        assertEquals(listOf(testnetUsdc), testnet.map { it.assetId })
        assertEquals(listOf(mainnetUsdc), mainnet.map { it.assetId })
        assertEquals("USDC", testnet.single().title)
        assertEquals(false, testnet.single().added)
    }

    @Test
    fun aVerifiedAssetTheAccountHoldsIsShownAsAdded() {
        val holdings = listOf(AsaHolding(testnetUsdc, BigInteger.ZERO, false))

        assertEquals(listOf(true), AddAssetRows.rows(ALGORAND_TESTNET, holdings, "").map { it.added })
    }

    @Test
    fun aSearchMatchesTheSymbolOrThePrefixOfTheIdIgnoringCaseAndSpaces() {
        assertEquals(listOf(testnetUsdc), AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "usd").map { it.assetId })
        assertEquals(listOf(testnetUsdc), AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "  USDC  ").map { it.assetId })
        // A prefix matches the verified asset, and the typed number is also offered as an ID the list does not hold.
        assertEquals(listOf(testnetUsdc, 1045UL), AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "1045").map { it.assetId })
        assertEquals(emptyList(), AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "algo"))
    }

    @Test
    fun aTypedIdThatTheVerifiedListDoesNotHoldIsOfferedWithItsWarning() {
        val row = AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "12345").single()

        assertEquals(12345UL, row.assetId)
        assertEquals("Asset 12345", row.title)
        assertEquals(false, row.added)
    }

    @Test
    fun aTypedIdOfAVerifiedAssetIsListedOnce() {
        assertEquals(listOf(testnetUsdc), AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), "10458941").map { it.assetId })
    }

    @Test
    fun onlyAPositiveWholeNumberIsOfferedAsAnId() {
        for (query in listOf("0", "abc", "1.5", "-3", "99999999999999999999999")) {
            assertEquals(emptyList(), AddAssetRows.rows(ALGORAND_TESTNET, emptyList(), query), query)
        }
    }
}
